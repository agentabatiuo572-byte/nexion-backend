package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.SupportBulk;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.dto.SupportBulkRequest;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.content.web.SupportBulkController;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportBulkCandidatesTest {
    final SupportBulkMapper mapper=mock(SupportBulkMapper.class);
    final SupportBindingMapper bindings=mock(SupportBindingMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final SupportActivityService activity=mock(SupportActivityService.class);
    final FinanceSupportReadService finance=mock(FinanceSupportReadService.class);
    final SupportAttachmentService attachments=mock(SupportAttachmentService.class);
    final SupportHumanMessageService messages=mock(SupportHumanMessageService.class);
    final OpsConversationService conversations=mock(OpsConversationService.class);
    final ObjectMapper json=new ObjectMapper();
    final ProductionSupportPathGuard production=mock(ProductionSupportPathGuard.class);
    final SupportBulkService service=new SupportBulkService(mapper,bindings,ownership,activity,finance,attachments,
        messages,conversations,mock(AdminIdempotencyService.class),production,mock(PlatformTransactionManager.class),
        json,mock(AuditLogService.class),mock(ApplicationEventPublisher.class),300);
    final List<Map<String,Object>> rows=new ArrayList<>();

    SupportBulkCandidatesTest() {
        when(attachments.actor("ADMIN")).thenReturn(7L);
        var at=LocalDateTime.of(2026,10,29,1,0);
        when(activity.checkpoint()).thenReturn(new SupportActivityService.Coverage(at.minusDays(40),at));
        when(bindings.rules()).thenReturn(new SupportRules(1L,30,7,30,"NONE",0));
        when(ownership.queryScope(ReadMode.PERSONAL,null,null)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        for(long id=1;id<=23;id++) {
            rows.add(new HashMap<>(Map.of("customerId",id,"assignmentId",id+100,"nickname","Customer "+id,
                "enabled",true,"keywordMatched",true,"accountState","ACTIVE","level","V3")));
            when(bindings.canonicalCustomer(id)).thenReturn(1);
        }
        when(mapper.candidates(anyMap())).thenReturn(rows);
    }

    @Test void pagesAreTenTenThreeWithStableOrderAndNoPersistentSelectionOrPhone() throws Exception {
        for(long page=1;page<=4;page++) {
            var result=service.candidates(new SupportBulkRequest.Candidates(null,page,10));
            assertThat(result.total()).isEqualTo(23);assertThat(result.selectableCount()).isEqualTo(23);
            assertThat(result.pageNum()).isEqualTo(page);assertThat(result.pageSize()).isEqualTo(10);
            assertThat(result.actorId()).isEqualTo(7);assertThat(result.evaluatedAt()).isEqualTo("2026-10-29T01:00:00Z");
            assertThat(result.records()).hasSize(page<3?10:page==3?3:0);
            if(page<=3) assertThat(result.records().get(0).id()).isEqualTo((page-1)*10+1);
            assertThat(json.writeValueAsString(result)).doesNotContain("phone","keywordMatched");
        }
        assertThat(service.candidates(new SupportBulkRequest.Candidates(null,null,null)).records().get(0).userNo()).isEqualTo("U00000001");
        verify(mapper,times(5)).candidates(argThat(q->new ReadScope(7L,ReadMode.PERSONAL,null,null).equals(q.get("scope"))));
        assertNoBulkWrites();
    }

    @Test void searchOnlyReturnsMatchesAndKeepsStoppedAndConvertedRowsDisabled() {
        rows.forEach(row->row.put("keywordMatched",false));
        rows.get(0).put("keywordMatched",true);rows.get(1).put("keywordMatched",true);rows.get(2).put("keywordMatched",true);
        rows.get(1).put("enabled",false);
        when(mapper.conversation(3L)).thenReturn(Map.of("converted",true));
        var result=service.candidates(new SupportBulkRequest.Candidates(filters("  817  ",null,null),1L,10));
        assertThat(result.total()).isEqualTo(3);assertThat(result.selectableCount()).isEqualTo(1);
        assertThat(result.records()).extracting(SupportBulk.Candidate::id).containsExactly(1L,2L,3L);
        assertThat(result.records()).extracting(SupportBulk.Candidate::reason).containsExactly(null,"MAINTENANCE_STOPPED","CONVERSATION_CONVERTED");
        verify(mapper).candidates(argThat(q->"817".equals(q.get("keyword"))));
        assertNoBulkWrites();
    }

    @Test void businessFiltersApplyBeforePaginationIncludingStoppedRowsAndFinancialFailuresStayDisabled() {
        rows.get(0).put("enabled",false); // Stopped but wrong tag must not appear in the matching page.
        when(mapper.hasTag(2L,"VIP")).thenReturn(1);
        when(mapper.hasTag(3L,"VIP")).thenReturn(1);
        when(finance.totals(2L)).thenReturn(Map.of("byCurrency",List.of(Map.of("currency","USDT",
            "creditedDepositTotal","12","successfulWithdrawalPrincipalTotal","0",
            "fieldStatuses",Map.of("creditedDepositTotal","READY","successfulWithdrawalPrincipalTotal","READY")))));
        when(finance.totals(3L)).thenThrow(new BizException(403,"FINANCE_FORBIDDEN"));
        var result=service.candidates(new SupportBulkRequest.Candidates(filters(null,List.of("VIP"),"10"),1L,10));
        assertThat(result.total()).isEqualTo(2);assertThat(result.selectableCount()).isEqualTo(1);
        assertThat(result.records()).extracting(SupportBulk.Candidate::id).containsExactly(2L,3L);
        assertThat(result.records()).extracting(SupportBulk.Candidate::reason).containsExactly(null,"FINANCE_FORBIDDEN");
        rows.get(1).put("accountState","DORMANT");rows.get(2).put("level","V2");
        assertThat(service.candidates(new SupportBulkRequest.Candidates(filters(null,List.of("VIP"),"10"),1L,10)).total()).isZero();
        assertNoBulkWrites();
    }

    @Test void invalidBoundariesAndFiltersRejectWithoutQuery() {
        for(var request:Arrays.asList(null,new SupportBulkRequest.Candidates(null,0L,10),
                new SupportBulkRequest.Candidates(null,1L,20),new SupportBulkRequest.Candidates(null,Long.MAX_VALUE,10),
                new SupportBulkRequest.Candidates(filters("x".repeat(201),null,null),1L,10))) {
            assertThatThrownBy(()->service.candidates(request)).isInstanceOf(BizException.class);
        }
        verifyNoInteractions(mapper,activity,bindings,ownership);
    }

    @Test void lostQualificationAndFailedSourcesNeverReturnAUsablePage() {
        doThrow(new BizException(403,"SUPPORT_AGENT_UNAVAILABLE")).when(ownership).requireSendingActor(7L);
        assertThatThrownBy(()->service.candidates(new SupportBulkRequest.Candidates(null,1L,10))).hasMessage("SUPPORT_AGENT_UNAVAILABLE");
        verifyNoInteractions(mapper,activity,bindings);
        doNothing().when(ownership).requireSendingActor(7L);
        when(bindings.rules()).thenReturn(null);
        assertThatThrownBy(()->service.candidates(new SupportBulkRequest.Candidates(null,1L,10))).hasMessage("SUPPORT_BULK_SOURCE_UNAVAILABLE");
        verifyNoInteractions(mapper);
        when(bindings.rules()).thenReturn(new SupportRules(1L,30,7,30,"NONE",0));
        when(mapper.candidates(anyMap())).thenThrow(new BizException(503,"DB_UNAVAILABLE"));
        assertThatThrownBy(()->service.candidates(new SupportBulkRequest.Candidates(null,1L,10))).hasMessage("DB_UNAVAILABLE");
        assertNoBulkWrites();
    }

    @Test void revocationDuringEvaluationRejectsAndProductionGuardPrecedesAnyRead() {
        doNothing().doThrow(new BizException(403,"SUPPORT_WRITE_FORBIDDEN")).when(ownership).requireSendingActor(7L);
        assertThatThrownBy(()->service.candidates(new SupportBulkRequest.Candidates(null,1L,10))).hasMessage("SUPPORT_WRITE_FORBIDDEN");
        assertNoBulkWrites();
        clearInvocations(mapper,activity,bindings,ownership,attachments);
        doThrow(new BizException(503,"SUPPORT_PATH_UNAVAILABLE")).when(production).requireOpsWriteAllowed();
        assertThatThrownBy(()->service.candidates(new SupportBulkRequest.Candidates(null,1L,10))).hasMessage("SUPPORT_PATH_UNAVAILABLE");
        verifyNoInteractions(mapper,activity,bindings,ownership,attachments);
    }

    @Test void explicitAllFilteredPreviewUsesSearchMatchesAndCrossPageStillRechecksEverySelectedId() {
        rows.forEach(row->row.put("keywordMatched",false));rows.get(0).put("keywordMatched",true);
        var preview=service.preview(new SupportBulkRequest.Preview(filters("817",null,null),List.of(),List.of(),"ALL_FILTERED"));
        assertThat(preview.customers()).extracting(SupportBulk.Customer::id).containsExactly(1L);
        assertThat(preview.excluded()).isEmpty();
        verify(mapper).insertRecipient(eq(preview.selectionId()),eq(1L),eq(101L),anyString());
        when(mapper.candidates(anyMap())).thenReturn(List.of(rows.get(0),rows.get(1)));
        var frozen=service.preview(new SupportBulkRequest.Preview(null,List.of(1L,2L,99L),List.of(),"CROSS_PAGE"));
        assertThat(frozen.customers()).extracting(SupportBulk.Customer::id).containsExactly(1L,2L);
        assertThat(frozen.excluded()).containsExactly(new SupportBulk.Excluded(99L,"NOT_CURRENT_CUSTOMER"));
        verify(mapper).candidates(argThat(q->List.of(1L,2L,99L).equals(q.get("ids")) && q.get("keyword")==null));
        verifyNoInteractions(messages,conversations);
    }

    @Test void sqlBindsNicknamePhoneAndBothNumberFormsInsidePersonalScopeWithoutProjectingPhone() throws Exception {
        var method=SupportBulkMapper.class.getMethod("candidates",Map.class);
        String source=String.join("\n",method.getAnnotation(Select.class).value());
        for(String keyword:List.of("Alice","0817","0020","U00000020","U123456789")) {
            var query=new HashMap<String,Object>();query.put("scope",new ReadScope(7L,ReadMode.PERSONAL,null,null));query.put("keyword",keyword);
            var bound=new XMLLanguageDriver().createSqlSource(new Configuration(),source,Map.class).getBoundSql(query);
            String sql=bound.getSql().replaceAll("\\s+"," ");
            assertThat(sql).contains("scope_binding.agent_admin_id=?","c.nickname LIKE CONCAT('%',?,'%')",
                "c.customerNo LIKE CONCAT('%',?,'%')","CONCAT('U',LPAD(c.customerNo,GREATEST(8,LENGTH(c.customerNo)),'0')) LIKE CONCAT('%',?,'%')",
                "u.phone LIKE CONCAT('%',?,'%')) keywordMatched");
            assertThat(sql).doesNotContain(keyword,"u.phone phone","phoneMasked");
            assertThat(sql.indexOf("scope_binding.agent_admin_id=?")).isLessThan(sql.indexOf("keywordMatched"));
            assertThat(bound.getParameterMappings().stream().filter(p->p.getProperty().equals("keyword"))).hasSize(5);
        }
        var route=SupportBulkController.class.getMethod("candidates",SupportBulkRequest.Candidates.class);
        assertThat(route.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('service_m3_write')");
    }

    private SupportBulkRequest.Filters filters(String keyword,List<String> tags,String depositMin) {
        return new SupportBulkRequest.Filters("ACTIVE",null,"V3",tags,null,null,null,null,depositMin,null,null,null,
            depositMin==null?null:"USDT",false,keyword);
    }
    private void assertNoBulkWrites() {
        assertThat(mockingDetails(mapper).getInvocations()).allSatisfy(call->assertThat(call.getMethod().getName())
            .isIn("candidates","conversation","hasTag"));
        verifyNoInteractions(messages,conversations);
    }
}
