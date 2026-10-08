package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorScope;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class SupportAgentMapperTicketAssigneeCandidateSqlContractTest {
    @Test
    void appAdvisorParsesEscapedEligibilityIntoOneSnapshotStatement() throws Exception {
        Method method = SupportAgentMapper.class.getDeclaredMethod("findAppAdvisor", Long.class);
        String source = String.join("\n", method.getAnnotation(Select.class).value());
        BoundSql bound = new XMLLanguageDriver().createSqlSource(new Configuration(), source, Map.class)
                .getBoundSql(Map.of("userId", 23L));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();

        assertThat(source.trim()).startsWith("<script>").endsWith("</script>");
        assertThat(sql).startsWith("SELECT assignmentId,currentAdvisorId,currentAdvisorName,")
                .contains("scope_q.qualification_kind='SERVICE'", "scope_q.state='ENABLED'",
                        "scope_q.starts_at <= UTC_TIMESTAMP(6)", "scope_q.ends_at IS NULL",
                        "scope_q_other.admin_id=scope_q.admin_id",
                        "scope_q_other.qualification_kind=scope_q.qualification_kind",
                        "scope_q_other.id<>scope_q.id", "scope_q_other.starts_at <= UTC_TIMESTAMP(6)",
                        "scope_q_other.ends_at>UTC_TIMESTAMP(6)", "a.status=1", "p.enabled=1",
                        "r.status=1", "r.role_code IN ('SUPPORT','SUPER_ADMIN')", "a.id=x.agent_admin_id")
                .doesNotContain("&lt;", "&gt;", "&amp;", "<script>", "</script>", "FOR SHARE", "FOR UPDATE", "${");
        assertThat(bound.getParameterMappings()).extracting("property").containsExactly("userId");
    }

    @Test
    void supportPageAndCountShareLatestActivePrimaryRoleAndActorScope() throws Exception {
        String count = sql("countSupportOperators", SupportOperatorScope.class);
        String page = sql("pageSupportOperators", SupportOperatorScope.class, long.class, long.class);
        String countWhere = count.substring(count.indexOf(" where scope_agent.is_deleted"), count.indexOf("</script>")).trim();
        String pageWhere = page.substring(page.indexOf(" where scope_agent.is_deleted"), page.indexOf(" order by scope_agent.id asc")).trim();

        assertThat(countWhere).isEqualTo(pageWhere)
                .contains("scope_agent.is_deleted = 0", "nx_support_group_member_history", "for share")
                .doesNotContain("where scope_agent.status = 1")
                .contains("primary_role.id in", "scope.supportroleids", "scope.unusableprimaryroleids")
                .contains("scope_agent.super_admin = 1 and (primary_role.id is null")
                .contains("<if test='scope != null and scope.visibleadminid != null'> and scope_agent.id = #{scope.visibleadminid} </if>");
        assertThat(count).contains("left join nx_admin_role primary_role on primary_role.id = (select rr.role_id")
                .contains("r.id = rr.role_id", "r.status = 1", "r.is_deleted = 0")
                .contains("rr.admin_id = scope_agent.id", "rr.is_deleted = 0")
                .contains("order by rr.updated_at desc, rr.id desc limit 1)");
        assertThat(count).startsWith("<script>select count(1) from nx_admin scope_agent")
                .doesNotContain("${");
        // The primary-role resolver and display projection remain narrow; current authorization
        // deliberately consults profiles and membership in the shared WHERE predicate.
        assertThat(count.substring(0, count.indexOf(" where scope_agent.is_deleted")))
                .doesNotContain("nx_admin_account_state", "nx_support_agent_profile", "exists(");
        assertThat(page).contains("order by scope_agent.id asc limit #{limit} offset #{offset}")
                .doesNotContain("${");
        assertThat(page.substring(0, page.indexOf(" from nx_admin scope_agent")))
                .doesNotContain("nx_support_agent_profile", "seat_type", "service_types", "transferable", "busy");
    }

    @Test
    void supportPageProjectsOnlyDisplayAndAvatarFieldsWithoutRequiringAnAccountState() throws Exception {
        String page = sql("pageSupportOperators", SupportOperatorScope.class, long.class, long.class);
        assertThat(page)
                .startsWith("<script> select scope_agent.id as adminid,")
                .contains("cast(scope_agent.id as char)) as name", "coalesce(trim(scope_agent.email), '') as email")
                .contains("st.avatar_asset_id as avatarassetid", "coalesce(st.avatar_version, 0) as avatarversion")
                .contains("left join nx_admin_account_state st on st.admin_id = scope_agent.id and st.is_deleted = 0")
                .doesNotContain("session", "audit", "tfa", "credential", " for update", " insert ", " create ");
    }

    @Test
    void currentManagedMembershipIsSharedByCountAndPageAndNullReadScopeDenies() throws Exception {
        var managed=new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(6L,
                ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED,10L,2L);
        var scope=new SupportOperatorScope(null,List.of(7L),List.of(),false,managed);
        for(String method:List.of("countSupportOperators","pageSupportOperators")) {
            BoundSql bound=boundSql(method,scope);
            assertThat(bound.getSql()).contains("nx_support_group_member_history","scope_owner.supervisor_admin_id=scope_group.supervisor_admin_id","FOR SHARE");
            assertThat(bound.getParameterMappings()).extracting("property")
                    .contains("scope.actorId","scope.requestedGroupId","scope.requestedAgentId");
            assertThat(boundSql(method,new SupportOperatorScope(null,List.of(7L),List.of(),false)).getSql()).contains("1=0");
        }
    }

    @Test
    void normalizedSupportRoleIdsAreBoundInBothRealMybatisStatements() throws Exception {
        SupportOperatorScope scope = new SupportOperatorScope(null, List.of(7L), List.of(), false);
        for (String method : List.of("countSupportOperators", "pageSupportOperators")) {
            BoundSql bound = boundSql(method, scope);
            assertThat(bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                    .contains("primary_role.id in ( ? )")
                    .doesNotContain("role_code = 'support'", "scope_agent.super_admin", "scope_agent.id = ?");
            assertThat(roleParameters(bound)).containsExactly(7L);
        }
        assertThat(sql("listActiveSupportRoleRows"))
                .contains("where status = 1 and is_deleted = 0 order by id asc");
    }

    @Test
    void superFallbackIsBoundOnlyForMissingOrUnusablePrimaryRolesWithSelfScope() throws Exception {
        SupportOperatorScope scope = new SupportOperatorScope(6L, List.of(2L), List.of(3L), true);
        for (String method : List.of("countSupportOperators", "pageSupportOperators")) {
            BoundSql bound = boundSql(method, scope);
            assertThat(bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                    .contains("or (scope_agent.super_admin = 1 and (primary_role.id is null or primary_role.id in ( ? ) ))")
                    .contains("and scope_agent.id = ?");
            assertThat(roleParameters(bound)).containsExactly(2L, 3L);
            assertThat(bound.getParameterMappings()).extracting("property").contains("scope.visibleAdminId");
        }
        BoundSql noFallback = boundSql("countSupportOperators", new SupportOperatorScope(null, List.of(2L), List.of(3L), false));
        assertThat(noFallback.getSql()).doesNotContain("scope_agent.super_admin");
        assertThat(roleParameters(noFallback)).containsExactly(2L);
        BoundSql empty = boundSql("countSupportOperators", new SupportOperatorScope(null, List.of(), List.of(), false));
        assertThat(empty.getSql()).contains("1=0").doesNotContain("scope_agent.super_admin");
    }

    private BoundSql boundSql(String methodName, SupportOperatorScope scope) throws Exception {
        Class<?>[] types = "countSupportOperators".equals(methodName)
                ? new Class<?>[] {SupportOperatorScope.class} : new Class<?>[] {SupportOperatorScope.class, long.class, long.class};
        Method method = SupportAgentMapper.class.getDeclaredMethod(methodName, types);
        String script = String.join(" ", method.getAnnotation(Select.class).value());
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class)
                .getBoundSql(Map.of("scope", scope, "limit", 5L, "offset", 0L));
    }

    private List<Object> roleParameters(BoundSql bound) {
        return bound.getParameterMappings().stream().map(mapping -> mapping.getProperty())
                .filter(property -> property.startsWith("__frch_roleId_"))
                .map(bound::getAdditionalParameter).toList();
    }

    private String sql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = SupportAgentMapper.class.getDeclaredMethod(methodName, parameterTypes);
        return String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    @Test
    void candidateProjectionIsAReadOnlyTwoFieldJoinWithoutSchemaOrSeedStatements() {
        Method method = Arrays.stream(SupportAgentMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("listTicketAssigneeCandidates"))
                .findFirst()
                .orElseThrow();
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();

        assertThat(sql)
                .startsWith("select distinct a.id as adminid")
                .contains(" as name from nx_admin a")
                .contains("join nx_admin_role_relation rr")
                .contains("join nx_admin_role r")
                .contains("join nx_support_agent_profile p")
                .contains("a.status = 1")
                .contains("r.role_code = 'support'")
                .contains("p.enabled = 1")
                .contains("p.transferable = 1")
                .contains("p.busy = 0")
                .contains("find_in_set('support'")
                .doesNotContain(" create ", " alter ", " insert ", " update ", " delete ", " for update");
        assertThat(sql.substring(0, sql.indexOf(" from ")))
                .doesNotContain("email", "username as", "role", "seat", "service", "tags", "busy", "capacity");
    }
}
