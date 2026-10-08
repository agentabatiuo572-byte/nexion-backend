package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.*;

import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Script/parameter contract only; actual current reads and locking require MySQL runtime evidence. */
class SupportScopeSqlContractTest {
    private final Configuration configuration=new Configuration();
    SupportScopeSqlContractTest() {
        configuration.addMapper(SupportBindingMapper.class);configuration.addMapper(SupportGroupMapper.class);
    }
    private org.apache.ibatis.mapping.BoundSql sql(Class<?> mapper,String method,ReadScope scope) {
        Map<String,Object> params=new HashMap<>();params.put("scope",scope);params.put("id",20L);params.put("agent",12L);
        return configuration.getMappedStatement(mapper.getName()+"."+method).getBoundSql(params);
    }
    private String customer(ReadScope scope) { return sql(SupportBindingMapper.class,"readableCustomer",scope).getSql(); }

    @Test void nullScopeCompilesAsDenialForEverySharedReader() {
        assertThat(customer(null)).contains("1=0").doesNotContain("scope_binding.status");
        for(String method:new String[]{"readableGroup","scopedGroups","readableAgent"})
            assertThat(sql(SupportGroupMapper.class,method,null).getSql()).contains("1=0");
    }

    @Test void personalCollectionHasNoManagedOwnerOrQueueUnionAndUsesBoundActor() {
        var scope=new ReadScope(11L,ReadMode.PERSONAL,null,null);
        var bound=sql(SupportBindingMapper.class,"readableCustomer",scope);
        assertThat(bound.getSql()).contains("qualification_kind='SERVICE'","scope_binding.agent_admin_id=?")
                .doesNotContain("nx_support_group_owner_history","nx_support_customer_route_history","${");
        assertThat(bound.getParameterMappings()).extracting(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                .contains("id","scope.actorId");
    }

    @Test void managedCollectionPreservesUnavailableMemberAssetsAndRejectsOverlappingIntervals() {
        String sql=customer(new ReadScope(11L,ReadMode.MANAGED,8L,null));
        assertThat(sql).contains("qualification_kind='SUPERVISOR'","scope_group.supervisor_admin_id=?",
                "scope_owner.supervisor_admin_id=scope_group.supervisor_admin_id","scope_member_other",
                "scope_binding_other","scope_route_other","scope_owner_other","scope_q_other",
                "scope_member.starts_at <= UTC_TIMESTAMP(6)","scope_owner.ends_at IS NULL",
                "scope_active_binding.status='ACTIVE'");
        assertThat(sql).doesNotContain("scope_binding.agent_admin_id=scope_actor.id","nx_support_agent_profile",
                "scope_member.enabled","LIMIT 1","MAX(","${");
        // Every mutable EXISTS/NOT EXISTS query block has its own locking clause, not just the outer query.
        assertThat(sql.split("FOR SHARE",-1).length).isGreaterThan(10);
    }

    @Test void allUnfilteredRequiresLiveAdminRoleWhileGroupAndAgentOnlyNarrow() {
        String all=customer(new ReadScope(11L,ReadMode.ALL,null,null));
        assertThat(all).contains("scope_actor.status=1","scope_role.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN')")
                .doesNotContain("nx_support_agent_user_assignment","nx_support_customer_route_history");
        assertThat(customer(new ReadScope(11L,ReadMode.ALL,null,12L)))
                .contains("scope_binding.agent_admin_id=?").doesNotContain("nx_support_customer_route_history");
        assertThat(customer(new ReadScope(11L,ReadMode.ALL,8L,null)))
                .contains("scope_group.id=?","scope_route.customer_id=scope_customer.id");
    }

    @Test void agentFilterDoesNotAccidentallyIncludeGroupQueue() {
        String sql=customer(new ReadScope(11L,ReadMode.MANAGED,8L,12L));
        assertThat(sql).contains("scope_binding.agent_admin_id=?").doesNotContain("nx_support_customer_route_history");
        assertThat(sql(SupportGroupMapper.class,"readableAgent",new ReadScope(11L,ReadMode.MANAGED,8L,12L)).getSql())
                .contains("scope_agent.id=?","scope_member.agent_admin_id=scope_agent.id")
                .doesNotContain("scope_agent.status=1","nx_support_agent_profile");
    }
    @Test void poolAndHandoverCountAndPageUseIdenticalCustomerScopeAndNullDenial() {
        var scope=new ReadScope(11L,ReadMode.MANAGED,null,null);
        for(String method:new String[]{"scopedPool","scopedPoolCount","scopedHandover","scopedHandoverCount"}) {
            assertThat(sql(SupportBindingMapper.class,method,null).getSql()).contains("1=0");
            assertThat(sql(SupportBindingMapper.class,method,scope).getSql())
                    .contains("scope_group.supervisor_admin_id=?","scope_owner_other","scope_q_other").doesNotContain("${");
        }
        assertThat(sql(SupportBindingMapper.class,"scopedPool",scope).getSql()).contains("active_pool_binding.status='ACTIVE'");
    }
    @Test void pendingExitUsesRealFrozenGroupRelationshipAndRunningStateRatherThanOnlyRoutes() {
        assertThat(sql(SupportGroupMapper.class,"pendingGroupOperations",null).getSql())
                .contains("JSON_TABLE(p.customers_json","'$.groupId'","running.status='RUNNING'","p.expires_at>UTC_TIMESTAMP(6)");
    }
}
