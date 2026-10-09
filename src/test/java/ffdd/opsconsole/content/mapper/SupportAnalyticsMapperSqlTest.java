package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Bound SQL is a structural contract; it does not attest MySQL results or lifetime coverage. */
class SupportAnalyticsMapperSqlTest {
    private final Configuration configuration=configuration();

    @Test void eventCandidatesAreAllHistoryWithSavedAgentAndGroupAuthorizationNotCurrentMembershipOrWindow() {
        String personal=sql("eventCandidates",new ReadScope(7L,ReadMode.PERSONAL,null,null));
        assertThat(personal).contains("FROM nx_support_payment_attribution e", "e.agent_status='KNOWN'", "e.agent_admin_id=?", "qualification_kind='SERVICE'");
        String managed=sql("eventCandidates",new ReadScope(8L,ReadMode.MANAGED,100L,7L));
        assertThat(managed).contains("e.group_status='KNOWN'", "scope_group.id=e.group_id", "scope_group.supervisor_admin_id=?",
            "scope_owner.supervisor_admin_id=scope_group.supervisor_admin_id", "scope_group.id=?", "e.agent_admin_id=?", "qualification_kind='SUPERVISOR'");
        assertThat(managed).doesNotContain("nx_support_group_member_history", "nx_support_agent_user_assignment", "JOIN nx_user", "succeeded_at", "currency", "LIMIT", "OFFSET");
        // The main financial event SELECT is ordinary; nested locks only revalidate live authorization.
        assertThat(managed).endsWith("ORDER BY e.fact_id");
        assertThat(bound("eventCandidates",new ReadScope(8L,ReadMode.MANAGED,100L,7L)).getParameterMappings())
            .extracting(p -> p.getProperty()).contains("scope.actorId","scope.requestedGroupId","scope.requestedAgentId");
    }

    @Test void currentListAndCountsUseOneProductionProjectionWithQueueAndConflictClassification() {
        String query=sql("currentCustomers",new ReadScope(8L,ReadMode.ALL,null,null));
        assertThat(query).startsWith("WITH assignments AS").contains("scope_customer.sandbox=0", "scope_customer.is_deleted=0",
            "'BOUND'", "'PENDING'", "'ANOMALY'", "'GROUPED'", "'UNGROUPED'", "'GROUP_QUEUE'", "'GLOBAL_QUEUE'", "'UNKNOWN'",
            "a.activeRows=1", "bi.intervalRows=1", "o.supervisorId=g.supervisor_admin_id", "handoverRequired",
            "LEFT JOIN assignments a", "LEFT JOIN routes r", "p.enabled=1", "q.state='ENABLED'");
        assertThat(query).doesNotContain("LIMIT", "OFFSET", "nx_wallet", "balance", "attribution_evidence_json");
        assertThat(query).endsWith("SELECT customerId,category,placement,handoverRequired,ownerAgentId,currentGroupId FROM classified ORDER BY customerId");
        assertThat(query).contains("THEN a.agentId ELSE NULL END ownerAgentId","ELSE NULL END currentGroupId");
    }

    @Test void platformLegacyEnumerationIncludesUnboundAndDeletedHistoricalProductionSubjectsWithoutGrantingOtherModes() {
        String all=sql("legacyProductionCustomers",new ReadScope(8L,ReadMode.ALL,null,null));
        assertThat(all).contains("SELECT u.id FROM nx_user u WHERE u.sandbox=0", "scope_role.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN')");
        assertThat(all).doesNotContain("u.is_deleted", "assignment", "member_history", "currency", "succeeded_at", "LIMIT");
        for(ReadScope scope:List.of(new ReadScope(7L,ReadMode.PERSONAL,null,null),new ReadScope(8L,ReadMode.MANAGED,null,null),
                new ReadScope(8L,ReadMode.ALL,100L,null),new ReadScope(8L,ReadMode.ALL,null,7L)))
            assertThat(sql("legacyProductionCustomers",scope)).contains("AND 1=0");
    }

    @Test void attributionReadsOnlyRequestedCanonicalIdentitiesAndFixedScalarsWithBothScopePaths() {
        String query=sql("attributions",new ReadScope(7L,ReadMode.PERSONAL,null,null));
        assertThat(query).contains("e.fact_id IN ( ? , ? )", "e.source_business_zone sourceBusinessZone", "e.fractional_second_digits fractionalSecondDigits",
            "e.original_fact_id originalFactId", "e.capture_mode captureMode", "e.capture_schema_version captureSchemaVersion",
            "e.agent_status='KNOWN'", "OR EXISTS(SELECT 1 FROM nx_user scope_customer", "scope_customer.id=e.customer_id", "scope_customer.sandbox=0");
        assertThat(query).doesNotContain("JSON_EXTRACT", "source_fact_json", "attribution_evidence_json", "nx_order", "nx_wallet_ledger", "LIMIT");
        assertThat(query).endsWith("ORDER BY e.fact_id");
        assertThat(bound("attributions",new ReadScope(7L,ReadMode.PERSONAL,null,null)).getParameterMappings().size()).isGreaterThan(2);
    }

    @Test void nullScopeNeverTurnsIntoAnUnrestrictedRead() {
        for(String method:List.of("currentCustomers","eventCandidates","legacyProductionCustomers","attributions"))
            assertThat(sql(method,null)).contains("1=0");
    }

    private BoundSql bound(String method,ReadScope scope) {
        Map<String,Object> parameters=new HashMap<>();parameters.put("scope",scope);parameters.put("factIds",List.of("DEPOSIT:1","PURCHASE:2"));
        parameters.put("customerIds",List.of(1L,2L));parameters.put("observedThroughAt",java.time.LocalDateTime.of(2026,10,9,12,0));
        return configuration.getMappedStatement(SupportAnalyticsMapper.class.getName()+"."+method).getBoundSql(parameters);
    }
    private String sql(String method,ReadScope scope) {return bound(method,scope).getSql().replaceAll("\\s+"," ").trim();}
    private static Configuration configuration() {var config=new Configuration();config.addMapper(SupportAnalyticsMapper.class);return config;}
    @Test void currentActivityUsesOnlyRequestedAuthorizedRootsThroughSavedWatermarkAndKeepsQueues() {
        String query=sql("activityEvents",new ReadScope(8L,ReadMode.MANAGED,null,null));
        assertThat(query).contains("FROM nx_support_activity_event e JOIN nx_user scope_customer", "scope_customer.sandbox=0","e.customer_id IN ( ? , ? )","e.occurred_at <= ?","MAX(e.occurred_at)");
        assertThat(query).contains("qualification_kind='SUPERVISOR'","nx_support_customer_route_history");
        assertThat(query).doesNotContain("JOIN nx_support_agent_user_assignment","nx_support_activity_state","nx_support_maintenance_execution","nx_user_device_runtime","LIMIT","OFFSET");
        assertThat(query).endsWith("GROUP BY e.customer_id ORDER BY e.customer_id");
        Map<String,Object> parameters=new HashMap<>();parameters.put("scope",new ReadScope(7L,ReadMode.PERSONAL,null,null));parameters.put("customerIds",List.of());parameters.put("observedThroughAt",java.time.LocalDateTime.of(2026,10,9,12,0));
        assertThat(configuration.getMappedStatement(SupportAnalyticsMapper.class.getName()+".activityEvents").getBoundSql(parameters).getSql()).contains("1=0");
    }
    @Test void ordinaryCoverageRulesAndRosterReadsKeepLiveScopeGuardsAndDoNotWriteOrLockOuterStatistics() {
        for(String method:List.of("activityCoverage","activityRules")) {
            String query=sql(method,new ReadScope(7L,ReadMode.PERSONAL,null,null));
            assertThat(query).contains("qualification_kind='SERVICE'").doesNotContain("FOR UPDATE","UPDATE nx_support","checkpoint");
            assertThat(query).doesNotEndWith("FOR SHARE");assertThat(sql(method,null)).contains("1=0");
        }
        String rules=sql("activityRules",new ReadScope(7L,ReadMode.PERSONAL,null,null));assertThat(rules).contains("activity_window_days activityWindowDays");
        String services=sql("serviceAccountRows",new ReadScope(8L,ReadMode.ALL,null,null));
        assertThat(services).contains("scope_role.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN')","p.seat_type IN ('GENERAL','DEDICATED')","q.qualification_kind= 'SERVICE'","q.ends_at>UTC_TIMESTAMP(6)","p.enabled profileEnabled");
        assertThat(services).doesNotContain("q.state='ENABLED'","p.enabled=1","FIND_IN_SET","MIN(","COUNT(*)","LIMIT");assertThat(services).endsWith("ORDER BY scope_agent.id,q.id,m.id");
        String managed=sql("supervisorAccountRows",new ReadScope(8L,ReadMode.MANAGED,null,null));assertThat(managed).contains("scope_agent.id=?","q.qualification_kind= 'SUPERVISOR'");
        assertThat(sql("supervisorAccountRows",new ReadScope(7L,ReadMode.PERSONAL,null,null))).contains("1=0");
        String groups=sql("scopedGroupRows",new ReadScope(8L,ReadMode.MANAGED,100L,null));assertThat(groups).contains("ownerVerified","scope_group.supervisor_admin_id=?","scope_group.id=?");assertThat(groups).endsWith("ORDER BY scope_group.id");
        for(String method:List.of("scopedGroupRows","serviceAccountRows","supervisorAccountRows"))assertThat(sql(method,null)).contains("1=0");
    }
}
