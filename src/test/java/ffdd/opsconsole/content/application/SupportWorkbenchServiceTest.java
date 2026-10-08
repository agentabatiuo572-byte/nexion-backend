package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.dto.SupportMaintenancePreferenceRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportWorkbenchMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SupportWorkbenchServiceTest {
    private static final LocalDateTime NOW=LocalDateTime.of(2026,9,29,10,0);
    private final SupportWorkbenchMapper mapper=mock(SupportWorkbenchMapper.class);
    private final SupportBindingMapper bindings=mock(SupportBindingMapper.class);
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final SupportActivityService activity=mock(SupportActivityService.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final SupportWorkbenchService service=new SupportWorkbenchService(mapper,bindings,ownership,activity,transactions,mock(SupportCustomerProfileService.class));

    private void setup(SupportRules rules,long unknownWindow) {
        when(ownership.actorId()).thenReturn(7L);
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        when(bindings.rules()).thenReturn(rules);
        when(activity.checkpoint()).thenReturn(new SupportActivityService.Coverage(NOW.minusDays(2),NOW));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var totals=new LinkedHashMap<String,Object>();
        for(String key:List.of("boundTotal","knownActiveCount","dormantTotal","unknownCount","dueTotal",
                "waitingReplyTotal","firstContactTotal","stoppedTotal","todoTotal")) totals.put(key,1L);
        totals.put("unknownWindowCount",unknownWindow);
        when(mapper.overview(any())).thenReturn(totals);
        when(mapper.count(any())).thenReturn(1L);
        when(mapper.customers(any())).thenReturn(List.of(new HashMap<>(Map.of("customerId",20L,"assignmentId",100L,
                "preferenceVersion",1L,"enabled",1,"waitingReply",1,"firstContact",1,"due",1))));
        when(mapper.executionDays(any())).thenReturn(List.of(Map.of("day","2026-09-29","executionCount",2L)));
        when(mapper.successDays(any())).thenReturn(List.of(Map.of("day","2026-09-29","successfulCycleCount",1L)));
        when(mapper.successfulCustomers(any())).thenReturn(1L);
    }

    @Test void onlyMConfiguredKeepsDueAndDoesNotFabricateActivityZero() {
        setup(new SupportRules(5L,null,2,null,"UNCONFIGURED",null),1);
        var result=service.snapshot(null,"DUE",null,1,20,null,null);
        assertThat(map(result.get("overview"))).containsEntry("activeTotal",null).containsEntry("dormantTotal",null)
                .containsEntry("dueTotal",1L).containsEntry("todoTotal",1L);
        assertThat(map(result.get("customers"))).containsEntry("available",true).containsEntry("total",1L);
        assertThat(result).containsEntry("rulesVersion",5L).containsEntry("evaluatedAt","2026-09-29T10:00:00Z");
        var captor=ArgumentCaptor.forClass(TransactionDefinition.class); verify(transactions).getTransaction(captor.capture());
        assertThat(captor.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        @SuppressWarnings("rawtypes") var query=ArgumentCaptor.forClass(Map.class);
        verify(mapper).overview(query.capture());
        assertThat(query.getValue()).containsEntry("scope",new ReadScope(7L,ReadMode.PERSONAL,null,null)).containsEntry("maintenanceDays",2)
                .containsEntry("dormantCutoff",null).containsEntry("windowCutoff",null);
    }

    @Test void incompleteWindowReportsKnownCountAndNullExactTotal() {
        setup(new SupportRules(6L,30,null,7,"UNCONFIGURED",null),1);
        var result=service.snapshot(null,"WINDOW_ACTIVE",null,2,1,null,null);
        assertThat(map(result.get("overview"))).containsEntry("knownActiveCount",1L).containsEntry("activeTotal",null)
                .containsEntry("unknownWindowCount",1L).containsEntry("dueTotal",null);
        var page=map(result.get("customers"));
        assertThat(page).containsEntry("total",1L).containsEntry("pageNum",2L);
        var row=map(((List<?>)page.get("records")).get(0));
        assertThat(row).containsEntry("due",null).containsEntry("waitingReply",true);
        var performance=map(result.get("performance"));
        assertThat(performance).containsEntry("timeZone","Asia/Shanghai").containsEntry("executionCount",2L)
                .containsEntry("successfulCycleCount",1L).containsEntry("successfulCustomerCount",1L);
        assertThat((List<?>)performance.get("days")).hasSize(29);
    }

    @Test void ordinaryAgentCannotSelectAnotherAgentAndNoCustomerQueryRuns() {
        setup(new SupportRules(1L,30,7,7,"UNCONFIGURED",null),0);
        when(ownership.defaultQueryScope(null,8L)).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
        assertThatThrownBy(()->service.snapshot(8L,"ALL",null,1,20,null,null)).isInstanceOf(BizException.class);
        verifyNoInteractions(mapper);
    }

    @Test void managedCardsAndPageUseOneExplicitModeAndPersonalDetailUsesItsOwnMode() {
        setup(new SupportRules(1L,30,7,7,"UNCONFIGURED",null),0);
        var managed=new ReadScope(7L,ReadMode.MANAGED,10L,null);
        when(ownership.queryScope(ReadMode.MANAGED,10L,null)).thenReturn(managed);
        service.snapshot(ReadMode.MANAGED,10L,null,"ALL","Alice",1,20,null,null);
        @SuppressWarnings("rawtypes") var queries=ArgumentCaptor.forClass(Map.class);
        verify(mapper).overview(queries.capture());verify(mapper).count(queries.capture());verify(mapper).customers(queries.capture());
        assertThat(queries.getAllValues()).allSatisfy(q->assertThat(q).containsEntry("scope",managed));
        var personal=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(ownership.customerQueryScope(20L)).thenReturn(personal);
        assertThat(map(service.detail(20L).get("scope"))).containsEntry("mode","PERSONAL");
    }

    @Test void historicalPerformanceUsesEventAgentScopeAndPreservesTransferredCustomerHistory() throws Exception {
        for(String method:List.of("executionDays","successDays","successfulCustomers")) {
            String source=String.join("\n",SupportWorkbenchMapper.class.getMethod(method,Map.class).getAnnotation(Select.class).value());
            var q=new HashMap<String,Object>();q.put("scope",new ReadScope(7L,ReadMode.PERSONAL,null,null));
            String sql=new XMLLanguageDriver().createSqlSource(new Configuration(),source,Map.class).getBoundSql(q).getSql();
            assertThat(sql).contains("JOIN nx_admin scope_agent","scope_agent.id=?","FOR SHARE")
                    .doesNotContain("scope_customer","nx_support_agent_user_assignment");
        }
    }

    @Test void safeIntegerAndStrictBooleanWireValidationRejectsCoercion() throws Exception {
        var json=new ObjectMapper();
        for(String invalid:List.of("\"1\"","1.5","1e2","0","9007199254740992")) {
            String body="{\"enabled\":true,\"expectedAssignmentId\":1,\"expectedVersion\":"+invalid+"}";
            assertThatThrownBy(()->json.readValue(body,SupportMaintenancePreferenceRequest.class)).isInstanceOf(Exception.class);
        }
        for(String invalid:List.of("\"true\"","1"))
            assertThatThrownBy(()->json.readValue("{\"enabled\":"+invalid+"}",SupportMaintenancePreferenceRequest.class)).isInstanceOf(Exception.class);
        assertThat(json.readValue("{\"enabled\":false,\"expectedVersion\":9007199254740991}",SupportMaintenancePreferenceRequest.class).expectedVersion())
                .isEqualTo(9007199254740991L);
        assertThatThrownBy(()->SupportWorkbenchService.requireSafeId(9007199254740992L)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->SupportWorkbenchService.validatePage(0,20)).isInstanceOf(BizException.class);
    }

    @Test void windowAndCurrentActiveHaveIndependentThresholdsAndFilters() throws Exception {
        var rules=new SupportRules(1L,30,7,7,"UNCONFIGURED",null);
        var last=NOW.minusDays(10); // W < age < D: current ACTIVE, outside WINDOW_ACTIVE.
        assertThat(last).isAfter(SupportWorkbenchService.cutoff(NOW,rules.dormantDays()))
                .isBefore(SupportWorkbenchService.cutoff(NOW,rules.activityWindowDays()));
        assertThat(SupportWorkbenchService.available("ACTIVE",new SupportRules(1L,30,null,null,"UNCONFIGURED",null))).isTrue();
        assertThat(SupportWorkbenchService.available("WINDOW_ACTIVE",new SupportRules(1L,30,null,null,"UNCONFIGURED",null))).isFalse();
        String active=sql("customers","ACTIVE");
        String window=sql("customers","WINDOW_ACTIVE");
        assertThat(active).contains("AND activityStatus='ACTIVE'");
        assertThat(window).contains("AND windowStatus='ACTIVE'").doesNotContain("AND activityStatus='ACTIVE'");
        assertThat(sql("customers","TODO")).contains("due=1 OR waitingReply=1 OR (firstContact=1 AND enabled=1)");
        assertThat(active).contains("scope_binding.agent_admin_id=?","m.id>COALESCE(r.through_message_id,0)","h.assignment_id=a.id",
                "h.actor_type='ADMIN'","h.actor_id=a.agent_admin_id","ORDER BY waitingReply DESC,nextDueAt IS NOT NULL,nextDueAt,customerId LIMIT ? OFFSET ?")
                .doesNotContain("unread_count","status='CLOSED'");
    }

    private static String sql(String method,String filter) throws Exception {
        String source=String.join("\n",SupportWorkbenchMapper.class.getMethod(method,Map.class).getAnnotation(Select.class).value());
        var query=new HashMap<String,Object>(); query.put("scope",new ReadScope(7L,ReadMode.PERSONAL,null,null)); query.put("filter",filter);
        return new XMLLanguageDriver().createSqlSource(new Configuration(),source,Map.class).getBoundSql(query).getSql();
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
}
