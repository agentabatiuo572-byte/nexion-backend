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
    void supportPageAndCountShareLatestActivePrimaryRoleAndActorScope() throws Exception {
        String count = sql("countSupportOperators", SupportOperatorScope.class);
        String page = sql("pageSupportOperators", SupportOperatorScope.class, long.class, long.class);
        String countWhere = count.substring(count.indexOf(" where a.status"), count.indexOf("</script>")).trim();
        String pageWhere = page.substring(page.indexOf(" where a.status"), page.indexOf(" order by a.id asc")).trim();

        assertThat(countWhere).isEqualTo(pageWhere)
                .contains("a.status = 1", "a.is_deleted = 0")
                .contains("primary_role.id in", "scope.supportroleids", "scope.unusableprimaryroleids")
                .contains("a.super_admin = 1 and (primary_role.id is null")
                .contains("<if test='scope.visibleadminid != null'> and a.id = #{scope.visibleadminid} </if>");
        assertThat(count).contains("left join nx_admin_role primary_role on primary_role.id = (select rr.role_id")
                .contains("r.id = rr.role_id", "r.status = 1", "r.is_deleted = 0")
                .contains("rr.admin_id = a.id", "rr.is_deleted = 0")
                .contains("order by rr.updated_at desc, rr.id desc limit 1)");
        assertThat(count).startsWith("<script>select count(1) from nx_admin a")
                .doesNotContain("nx_admin_account_state", "nx_support_agent_profile", "exists(", "${");
        assertThat(page).contains("order by a.id asc limit #{limit} offset #{offset}")
                .doesNotContain("nx_support_agent_profile", "seat_type", "service_types", "transferable", "busy", "${");
    }

    @Test
    void supportPageProjectsOnlyDisplayAndAvatarFieldsWithoutRequiringAnAccountState() throws Exception {
        String page = sql("pageSupportOperators", SupportOperatorScope.class, long.class, long.class);
        assertThat(page)
                .startsWith("<script> select a.id as adminid,")
                .contains("cast(a.id as char)) as name", "coalesce(trim(a.email), '') as email")
                .contains("st.avatar_asset_id as avatarassetid", "coalesce(st.avatar_version, 0) as avatarversion")
                .contains("left join nx_admin_account_state st on st.admin_id = a.id and st.is_deleted = 0")
                .doesNotContain("session", "audit", "tfa", "credential", " for update", " insert ", " create ");
    }

    @Test
    void normalizedSupportRoleIdsAreBoundInBothRealMybatisStatements() throws Exception {
        SupportOperatorScope scope = new SupportOperatorScope(null, List.of(7L), List.of(), false);
        for (String method : List.of("countSupportOperators", "pageSupportOperators")) {
            BoundSql bound = boundSql(method, scope);
            assertThat(bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                    .contains("primary_role.id in ( ? )")
                    .doesNotContain("role_code = 'support'", "a.super_admin", "a.id = ?");
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
                    .contains("or (a.super_admin = 1 and (primary_role.id is null or primary_role.id in ( ? ) ))")
                    .contains("and a.id = ?");
            assertThat(roleParameters(bound)).containsExactly(2L, 3L);
            assertThat(bound.getParameterMappings()).extracting("property").contains("scope.visibleAdminId");
        }
        BoundSql noFallback = boundSql("countSupportOperators", new SupportOperatorScope(null, List.of(2L), List.of(3L), false));
        assertThat(noFallback.getSql()).doesNotContain("a.super_admin");
        assertThat(roleParameters(noFallback)).containsExactly(2L);
        BoundSql empty = boundSql("countSupportOperators", new SupportOperatorScope(null, List.of(), List.of(), false));
        assertThat(empty.getSql()).contains("1=0").doesNotContain("a.super_admin");
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
