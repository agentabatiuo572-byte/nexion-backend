package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.content.web.SupportWorkbenchController;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import static ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import static ffdd.opsconsole.content.application.SupportAnalyticsPrivateQueryServiceTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Controlled source inputs; real Stats -> query version/sort/page -> MVC/advice. SQL and login are separate gates. */
class SupportAnalyticsCompletePagingTest {
    private static final String PATH=ffdd.opsconsole.common.api.OpsAdminApi.ADMIN_PREFIX+"/content/support-workbench/analytics";
    private static final ObjectMapper JSON=new ObjectMapper();

    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}

    @Test void readyFullSetSortsAcrossThreePagesAndRejectsChangedMembership() throws Exception {
        authenticate("service_m1_read");
        var chain=new SupportAnalyticsPrivateQueryFixesTest.RealStats();
        var f=chain.f;
        var ids=new ArrayList<>(List.of(100L,101L,102L,103L,104L));
        Map<Long,LocalDateTime> active=Map.of(100L,T.minusDays(3),101L,T.minusDays(2),102L,T.minusDays(2),103L,T);
        when(f.mapper.currentCustomers(f.scope)).thenAnswer(inv->ids.stream()
            .map(id->new CurrentCustomer(id,"BOUND","UNGROUPED",0,1L,null)).toList());
        var snapshot=f.capture.snapshot;
        when(chain.finance.readHistory(anyList())).thenAnswer(inv->new SupportPaymentFacts.Snapshot(List.of(),List.of(),snapshot.coverage(),snapshot.businessZone(),T.toInstant(java.time.ZoneOffset.ofHours(8)),
            ids.stream().map(id->new SupportPaymentFacts.FirstHistory(id,SupportPaymentFacts.Status.READY,List.of())).toList()));
        when(chain.invitations.readInvitations(anyList())).thenAnswer(inv->ids.stream().map(id->new SupportInvitationReadFacade.Invitation(id,0,List.of(),List.of(),SupportInvitationReadFacade.Completeness.COMPLETE,Set.of())).toList());
        when(chain.devices.readCurrent(anyList())).thenReturn(f.capture.stock);
        when(f.mapper.assignmentStamps(eq(f.scope),anyCollection())).thenAnswer(inv->ids.stream()
            .map(id->new RelationshipStamp("ASSIGNMENT",id+1000,id,1L,null,null,"ACTIVE",0,1L,T.minusDays(10),null)).toList());
        when(f.mapper.rootDisplayRows(eq(f.scope),anyCollection())).thenAnswer(inv->ids.stream()
            .map(id->new RootDisplayRow(id,"Customer "+id,"U00000"+id,"ACTIVE",T.minusDays(20),T,T.minusDays(10),null,null)).toList());
        when(f.mapper.activityEvents(eq(f.scope),anyList(),eq(T))).thenAnswer(inv->ids.stream().filter(active::containsKey)
            .map(id->new ActivityRow(id,active.get(id))).toList());
        when(f.mapper.activityIdentityRows(eq(f.scope),anyCollection(),eq(T))).thenAnswer(inv->ids.stream().filter(active::containsKey)
            .map(id->new ActivityIdentityRow(id,id+2000,1L,"LOGIN:"+id,active.get(id))).toList());
        when(f.mapper.taskRows(eq(f.scope),anyCollection(),eq(T),any(),any(),any(),eq(7),isNull(),eq(T))).thenAnswer(inv->ids.stream()
            .map(id->new TaskRow(id,1,1L,null,null,null,null,1,null,null,0L,0,0,null,"ACTIVE",active.containsKey(id)?"ACTIVE":"DORMANT",T,0,null,null,null)).toList());
        var funds=mock(SupportFundsReadFacade.class);
        var walletVersion=new AtomicLong(1);
        when(funds.readCurrent(anyCollection())).thenAnswer(inv->new Snapshot(ids,
            new WalletRead(ids.stream().map(id->new WalletEvidence(id,id+3000,id==104?walletVersion.get():1,
                BigDecimal.ZERO,BigDecimal.ZERO,T,EvidenceState.READY,List.of())).toList(),ReadState.READY,List.of(),T),
            new WithdrawalRead(List.of(),ReadState.READY,List.of(),T,CoverageState.UNKNOWN,CoverageState.UNKNOWN),"Asia/Shanghai"));
        when(funds.readNetContributions(anyCollection())).thenAnswer(inv->new NetSnapshot(ids.stream()
            .map(id->new NetCustomer(id,EvidenceState.READY,List.of(),BigDecimal.ZERO,BigDecimal.ZERO)).toList(),List.of(),List.of()));
        var stats=new SupportAnalyticsService(f.ownership,chain.finance,f.mapper,chain.invitations,chain.devices,funds);
        var api=new SupportAnalyticsPrivateQueryService(f.ownership,stats,f.mapper,funds);
        var controller=new SupportWorkbenchController(mock(SupportWorkbenchService.class),mock(SupportMaintenanceService.class),
            mock(ProductionSupportPathGuard.class),mock(SupportCustomerProfileService.class),api,f.ownership,
            mock(ffdd.opsconsole.user.application.OpsUser360Service.class));
        var mvc=standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class))).build();

        String descendingVersion=null;
        for(String direction:List.of("ASC","DESC")) {
            String version=null;
            var all=new ArrayList<String>();
            for(int page=1;page<=3;page++) {
                var data=page(mvc,direction,page,version);
                assertThat(data.path("versionState").asText()).isEqualTo("READY");
                assertThat(data.path("total").isIntegralNumber()).isTrue();
                assertThat(data.path("total").asInt()).isEqualTo(5);
                var next=data.path("queryVersion").asText();
                assertThat(next).matches("saq-v1:[0-9a-f]{64}");
                if(version!=null)assertThat(next).isEqualTo(version);
                version=next;
                assertThat(data.path("records").size()).isEqualTo(page==3?1:2);
                data.path("records").forEach(row->all.add(row.path("customerId").asText()));
                if(page==3)assertThat(data.path("records").get(0).path("activity").path("lastEffectiveAt").isNull()).isTrue();
            }
            assertThat(all).doesNotHaveDuplicates().containsExactlyElementsOf(direction.equals("ASC")
                ?List.of("100","101","102","103","104"):List.of("103","101","102","100","104"));
            if(direction.equals("DESC"))descendingVersion=version;
        }

        walletVersion.incrementAndGet(); // A wallet outside page one participates in the same version.
        assertConflict(mvc,descendingVersion);
        var walletRefresh=page(mvc,"DESC",1,null);
        assertThat(walletRefresh.path("versionState").asText()).isEqualTo("READY");
        assertThat(walletRefresh.path("queryVersion").asText()).matches("saq-v1:[0-9a-f]{64}").isNotEqualTo(descendingVersion);
        descendingVersion=walletRefresh.path("queryVersion").asText();
        ids.remove(104L); // A change outside page one must still invalidate the whole result set.
        assertConflict(mvc,descendingVersion);
        var refreshed=page(mvc,"DESC",1,null);
        assertThat(refreshed.path("versionState").asText()).isEqualTo("READY");
        assertThat(refreshed.path("total").asInt()).isEqualTo(4);
        assertThat(refreshed.path("queryVersion").asText()).matches("saq-v1:[0-9a-f]{64}").isNotEqualTo(descendingVersion);
        assertThat(refreshed.path("records").findValuesAsText("customerId")).containsExactly("103","101");
        verify(funds,atLeastOnce()).readCurrent(argThat(roots->roots.size()==5 && new HashSet<>(roots).equals(Set.of(100L,101L,102L,103L,104L))));
        verify(funds,atLeastOnce()).readNetContributions(List.of(100L,101L,102L,103L,104L));
        verifyNoInteractions(f.stats);
    }

    private static void assertConflict(MockMvc mvc,String version) throws Exception {
        var conflict=mvc.perform(get(PATH).param("sortKey","LAST_ACTIVE_AT").param("direction","DESC")
            .param("pageSize","2").param("pageNum","2").param("expectedVersion",version))
            .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(conflict).contains("SUPPORT_ANALYTICS_QUERY_CHANGED").doesNotContain("queryVersion","evidence");
    }

    private static JsonNode page(MockMvc mvc,String direction,int number,String version) throws Exception {
        var request=get(PATH).param("sortKey","LAST_ACTIVE_AT").param("direction",direction)
            .param("pageSize","2").param("pageNum",Integer.toString(number));
        if(version!=null)request.param("expectedVersion",version);
        return JSON.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
