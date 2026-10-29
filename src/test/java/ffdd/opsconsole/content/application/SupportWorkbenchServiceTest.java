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

    @org.junit.jupiter.api.AfterEach void restoreCalendar() {
        ffdd.opsconsole.shared.config.BusinessCalendar.replaceVersions(ffdd.opsconsole.shared.config.BusinessCalendar.seed());
    }

    private static void switchToTokyo(java.time.LocalDate date) {
        var versions=new java.util.ArrayList<>(ffdd.opsconsole.shared.config.BusinessCalendar.seed());
        versions.add(new ffdd.opsconsole.shared.config.BusinessCalendar.Version(ffdd.opsconsole.shared.config.BusinessCalendar.GLOBAL,
                java.time.ZoneId.of("Asia/Tokyo"),date,date.atStartOfDay().minusHours(1)));
        ffdd.opsconsole.shared.config.BusinessCalendar.replaceVersions(versions);
    }

    @Test void analyticsMonthsKeepTheZoneEachMonthWasSettledIn() {
        switchToTokyo(java.time.LocalDate.of(2026,12,1));
        var months=SupportWorkbenchService.analyticsMonths(ffdd.opsconsole.shared.config.BusinessCalendar.legacy(),
                java.time.Instant.parse("2027-01-15T00:00:00Z"),3);
        assertThat(months).extracting(m->m.get("month")).containsExactly("2027-01","2026-12","2026-11");
        assertThat(months.get(2)).containsEntry("from","2026-10-31T16:00:00Z").containsEntry("to","2026-11-30T15:00:00Z");
        assertThat(months.get(1)).containsEntry("from","2026-11-30T15:00:00Z").containsEntry("to","2026-12-31T15:00:00Z");
    }

    @Test void maintenanceTrendQueriesEachZoneSegmentWithItsOwnOffset() {
        setup(new SupportRules(5L,null,2,null,"UNCONFIGURED",null),0);
        switchToTokyo(java.time.LocalDate.of(2026,9,29));
        service.snapshot(null,"ALL",null,1,20,"2026-09-27T16:00:00Z","2026-09-29T15:00:00Z");
        @SuppressWarnings({"rawtypes","unchecked"}) ArgumentCaptor<Map<String,Object>> query=ArgumentCaptor.forClass((Class)Map.class);
        verify(mapper,org.mockito.Mockito.times(2)).executionDays(query.capture());
        assertThat(query.getAllValues()).extracting(q->q.get("businessOffset"),q->q.get("from"),q->q.get("to")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("+08:00",LocalDateTime.of(2026,9,27,16,0),LocalDateTime.of(2026,9,28,15,0)),
                org.assertj.core.groups.Tuple.tuple("+09:00",LocalDateTime.of(2026,9,28,15,0),LocalDateTime.of(2026,9,29,15,0)));
    }

    @Test void customerAvatarReferenceOnlyForTheCustomersOwnUpload() {
        var rules=new SupportRules(5L,null,2,null,"UNCONFIGURED",null);
        Map<String,Object> base=Map.of("customerId",20L,"preferenceVersion",1L,"enabled",1,"waitingReply",0,"firstContact",0,"due",0);
        var own=new HashMap<>(base); own.put("customerAvatarKey","users/20/avatar/a.png");
        var other=new HashMap<>(base); other.put("customerAvatarKey","users/21/avatar/a.png");
        assertThat(SupportWorkbenchService.customerView(own,rules))
                .containsEntry("customerAvatarRef","/api/admin/content/support-workbench/customers/20/avatar")
                .doesNotContainKey("customerAvatarKey");
        assertThat(SupportWorkbenchService.customerView(other,rules)).containsEntry("customerAvatarRef",null);
        assertThat(SupportWorkbenchService.customerView(new HashMap<>(base),rules)).containsEntry("customerAvatarRef",null);
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

    @Test void snapshotPublishesCurrentIndependentModesWithoutChangingSelectedScope() {
        setup(new SupportRules(5L,null,2,null,"UNCONFIGURED",null),0);
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(7L,ReadMode.MANAGED,null,null));
        when(ownership.availableReadModes()).thenReturn(List.of(ReadMode.MANAGED,ReadMode.PERSONAL));
        var result=service.snapshot(null,"ALL",null,1,1,null,null);
        assertThat(map(result.get("scope"))).containsEntry("mode","MANAGED")
                .containsEntry("availableModes",List.of("MANAGED","PERSONAL"));
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
        verifyNoInteractions(mapper,activity,transactions);
    }

    @Test void forbiddenGroupAndTransferredCustomerDoNotCheckpointActivity() {
        when(ownership.queryScope(ReadMode.MANAGED,10L,null)).thenThrow(new BizException(404,"SUPPORT_GROUP_NOT_FOUND"));
        when(ownership.customerQueryScope(20L)).thenThrow(new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND"));
        assertThatThrownBy(()->service.snapshot(ReadMode.MANAGED,10L,null,"ALL",null,1,20,null,null))
                .isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(404));
        assertThatThrownBy(()->service.detail(20L))
                .isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(404));
        verifyNoInteractions(activity,transactions,mapper,bindings);
    }

    @Test void customerAccessIsRecheckedAfterActivityCheckpoint() {
        when(ownership.customerQueryScope(20L)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null))
                .thenThrow(new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND"));
        when(activity.checkpoint()).thenReturn(new SupportActivityService.Coverage(NOW.minusDays(2),NOW));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        assertThatThrownBy(()->service.detail(20L)).isInstanceOf(BizException.class);
        var order=inOrder(ownership,activity,transactions);
        order.verify(ownership).customerQueryScope(20L);
        order.verify(activity).checkpoint();
        order.verify(transactions).getTransaction(any());
        order.verify(ownership).customerQueryScope(20L);
        verifyNoInteractions(mapper,bindings);
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

    @Test void contactSearchUsesTenRowPageAndSameTrimmedKeywordForCountAndRows() {
        setup(new SupportRules(1L,30,7,7,"UNCONFIGURED",null),0);
        var personal=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(ownership.queryScope(ReadMode.PERSONAL,null,null)).thenReturn(personal);
        var result=service.snapshot(ReadMode.PERSONAL,null,null,"ALL","  U00000020  ",2,10,null,null);
        @SuppressWarnings("rawtypes") var queries=ArgumentCaptor.forClass(Map.class);
        verify(mapper).count(queries.capture());verify(mapper).customers(queries.capture());
        assertThat(queries.getAllValues()).allSatisfy(q->assertThat(q)
                .containsEntry("scope",personal).containsEntry("keyword","U00000020")
                .containsEntry("limit",10).containsEntry("offset",10L));
        assertThat(queries.getAllValues().get(0)).isSameAs(queries.getAllValues().get(1));
        var page=map(result.get("customers"));
        assertThat(page).containsEntry("pageNum",2L).containsEntry("pageSize",10).containsEntry("total",1L);
        assertThat(map(((List<?>)page.get("records")).get(0))).doesNotContainKeys("phone","phoneMasked");
        var order=inOrder(ownership,activity,mapper);
        order.verify(ownership).queryScope(ReadMode.PERSONAL,null,null);
        order.verify(activity).checkpoint();
        order.verify(ownership).queryScope(ReadMode.PERSONAL,null,null);
        order.verify(mapper).overview(any());order.verify(mapper).count(any());order.verify(mapper).customers(any());
    }

    @Test void blankContactSearchRestoresUnfilteredFirstPage() {
        setup(new SupportRules(1L,30,7,7,"UNCONFIGURED",null),0);
        service.snapshot(null,"ALL","  ",1,10,null,null);
        @SuppressWarnings("rawtypes") var query=ArgumentCaptor.forClass(Map.class);
        verify(mapper).customers(query.capture());
        assertThat(query.getValue()).containsEntry("keyword",null).containsEntry("offset",0L).containsEntry("limit",10);
    }

    @Test void contactSearchBindsAllFourPartialMatchesAfterScopeWithoutProjectingPhone() throws Exception {
        for(String keyword:List.of("Alice","0817","0020","U00000020","U123456789")) {
            var query=new HashMap<String,Object>();
            query.put("scope",new ReadScope(7L,ReadMode.PERSONAL,null,null));query.put("filter","ALL");
            query.put("keyword",keyword);query.put("limit",10);query.put("offset",10L);
            var count=boundSql("count",query);var rows=boundSql("customers",query);
            String countSql=count.getSql().replaceAll("\\s+"," ");
            String rowsSql=rows.getSql().replaceAll("\\s+"," ");
            String filter=countSql.substring(countSql.indexOf("FROM customers WHERE 1=1"));
            assertThat(rowsSql).contains(filter+" ORDER BY waitingReply DESC,nextDueAt IS NOT NULL,nextDueAt,customerId LIMIT ? OFFSET ?");
            assertThat(filter).contains("nickname LIKE CONCAT('%',?,'%')","customerNo LIKE CONCAT('%',?,'%')",
                    "CONCAT('U',LPAD(customerNo,GREATEST(8,LENGTH(customerNo)),'0')) LIKE CONCAT('%',?,'%')",
                    "EXISTS (SELECT 1 FROM nx_user keyword_user WHERE keyword_user.id=customers.customerId AND keyword_user.phone LIKE CONCAT('%',?,'%'))");
            assertThat(rowsSql.indexOf("scope_binding.agent_admin_id=?")).isLessThan(rowsSql.indexOf(filter));
            assertThat(rowsSql.substring(0,rowsSql.indexOf("SELECT * FROM customers"))).doesNotContain("phone");
            assertThat(rowsSql).doesNotContain(keyword);
            for(var bound:List.of(count,rows)) {
                assertThat(bound.getParameterMappings().stream().filter(p->p.getProperty().equals("keyword"))).hasSize(4);
                assertThat(((Map<?,?>)bound.getParameterObject()).get("keyword")).isEqualTo(keyword);
            }
            assertThat(rows.getParameterMappings()).extracting(p->p.getProperty()).endsWith("limit","offset");
        }
    }

    @Test void customerDetailEntersReadOnlyRepeatableReadAfterActivityCheckpoint() {
        setup(new SupportRules(1L,30,7,7,"UNCONFIGURED",null),0);
        when(ownership.customerQueryScope(20L)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        service.detail(20L);
        var definition=ArgumentCaptor.forClass(TransactionDefinition.class);
        var order=inOrder(ownership,activity,transactions);
        order.verify(ownership).customerQueryScope(20L);
        order.verify(activity).checkpoint();
        order.verify(transactions).getTransaction(definition.capture());
        order.verify(ownership).customerQueryScope(20L);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
        var query=new HashMap<String,Object>(); query.put("scope",new ReadScope(7L,ReadMode.PERSONAL,null,null)); query.put("filter",filter);
        return boundSql(method,query).getSql();
    }

    private static org.apache.ibatis.mapping.BoundSql boundSql(String method,Map<String,Object> query) throws Exception {
        String source=String.join("\n",SupportWorkbenchMapper.class.getMethod(method,Map.class).getAnnotation(Select.class).value());
        return new XMLLanguageDriver().createSqlSource(new Configuration(),source,Map.class).getBoundSql(query);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
}
