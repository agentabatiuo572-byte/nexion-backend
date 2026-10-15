package ffdd.opsconsole.shared.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SupportRbacSeedContractTest {
    @Test
    void everySupportMenuDefaultSourceUsesOnlyTheExactServicePages() throws Exception {
        var statements = statements("01-menu-seed.sql");
        var sources = supportSources(statements, "nx_admin_role_menu");
        assertThat(sources).hasSize(1);
        assertThat(values(sources.get(0), "m.menu_code")).containsExactly("M", "M1", "M2", "M3", "M4", "M5");
        assertThat(sources.get(0)).contains("WHERE r.role_code='SUPPORT'").doesNotContain("LIKE");
        assertThat(statements).contains("DELETE rm FROM nx_admin_role_menu rm JOIN nx_admin_role r ON r.id=rm.role_id AND r.role_code='SUPPORT'");
        assertThat(statements.indexOf(sources.get(0))).isGreaterThan(statements.indexOf(
                "DELETE rm FROM nx_admin_role_menu rm JOIN nx_admin_role r ON r.id=rm.role_id AND r.role_code='SUPPORT'"));
    }

    @Test
    void everySupportPermissionDefaultSourceUsesOnlyTheEightExplicitServiceAuthorities() throws Exception {
        var statements = statements("02-role-permission-seed.sql");
        var sources = supportSources(statements, "nx_admin_role_permission");
        assertThat(sources).hasSize(1);
        assertThat(values(sources.get(0), "p.permission_code")).containsExactly(
                "service_m1_read", "service_m1_write", "service_m2_read", "service_m2_write",
                "service_m3_read", "service_m3_write", "service_m4_read", "service_m5_read");
        assertThat(sources.get(0)).contains("WHERE r.role_code='SUPPORT'", "p.status=1 AND p.is_deleted=0").doesNotContain("LIKE");
        String cleanup = "DELETE rp FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='SUPPORT'";
        assertThat(statements).contains(cleanup);
        assertThat(statements.indexOf(sources.get(0))).isGreaterThan(statements.indexOf(cleanup));
    }

    @Test
    void narrowingSupportKeepsTheOtherRolesInEachPreviouslySharedGrantSource() throws Exception {
        String menus = String.join(";", statements("01-menu-seed.sql"));
        assertThat(menus).contains(
                "m.menu_code LIKE 'C%' WHERE r.role_code='RISK'",
                "m.menu_code LIKE 'E%' WHERE r.role_code='GROWTH'",
                "m.menu_code LIKE 'I%' WHERE r.role_code='CONTENT'",
                "m.menu_code LIKE 'M%' WHERE r.role_code='RISK'",
                "m.menu_code IN ('M','M5') WHERE r.role_code='CONTENT'",
                "m.menu_code LIKE 'B%' WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','CONFIG_ADMIN')");
        String permissions = String.join(";", statements("02-role-permission-seed.sql"));
        assertThat(permissions).contains(
                "WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','CONFIG_ADMIN') AND p.permission_code NOT LIKE 'overview_b1_%'",
                "WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','AUDITOR') AND p.permission_code='user_c3_read'",
                "WHERE r.role_code IN ('FINANCE','FINANCE_LEAD') AND p.permission_code='user_c3_adjust_create'",
                "WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR') AND p.permission_code='finance_d4_user_read'",
                "p.permission_code LIKE 'service_%' WHERE r.role_code='RISK' AND (r.role_code <> 'RISK' OR p.permission_code <> 'service_m5_write') AND p.permission_code <> 'service_m3_timeout_manage'",
                "WHERE r.role_code='CONTENT' AND p.permission_code IN ('service_m5_read','service_m5_write')");
    }

    @Test
    void actualStartupSequenceRetiresImmutableHistoricalSupportGrantsAfterTheClassicSeed() throws Exception {
        String installer = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        var paths = Pattern.compile("\"(scripts\\\\migrations\\\\[^\"]+\\.sql)\"").matcher(installer);
        var migrations = new java.util.ArrayList<Path>();
        while (paths.find()) migrations.add(Path.of(paths.group(1).replace('\\', '/')));
        assertThat(migrations).isNotEmpty();
        assertThat(migrations.get(0)).isEqualTo(Path.of("scripts/migrations/20260712_rbac_classic.sql"));
        assertThat(migrations).contains(Path.of("scripts/migrations/20260720_d2_withdrawal_closure.sql"));
        var sequence = new java.util.ArrayList<String>();
        for (Path migration : migrations) sequence.addAll(expandedStatements(migration));
        var permissions = supportSources(sequence, "nx_admin_role_permission");
        var menus = supportSources(sequence, "nx_admin_role_menu");
        assertThat(permissions).hasSize(2);
        assertThat(values(permissions.get(0), "p.permission_code")).containsExactly(
                "service_m1_read", "service_m1_write", "service_m2_read", "service_m2_write",
                "service_m3_read", "service_m3_write", "service_m4_read", "service_m5_read");
        assertThat(menus).hasSize(2);
        assertThat(values(menus.get(0), "m.menu_code")).containsExactly("M", "M1", "M2", "M3", "M4", "M5");
        assertThat(permissions.get(1)).contains("p.permission_code='finance_d2_read'");
        assertThat(values(menus.get(1), "m.menu_code")).containsExactly("D", "D2");
        var retirement = statements(Path.of("scripts/migrations/20261015_support_d2_grant_retirement.sql"));
        assertThat(retirement).containsExactly(
                "DELETE rp FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code='SUPPORT' AND p.permission_code='finance_d2_read'",
                "DELETE rm FROM nx_admin_role_menu rm JOIN nx_admin_role r ON r.id=rm.role_id JOIN nx_admin_menu m ON m.id=rm.menu_id WHERE r.role_code='SUPPORT' AND m.menu_code IN ('D','D2')");
        assertThat(sequence).containsAll(retirement);
        assertThat(sequence.indexOf(retirement.get(0))).isGreaterThan(sequence.indexOf(permissions.get(1)));
        assertThat(sequence.indexOf(retirement.get(1))).isGreaterThan(sequence.indexOf(menus.get(1)));
    }

    private static List<String> expandedStatements(Path path) throws Exception {
        var result = new java.util.ArrayList<String>();
        for (String statement : statements(path)) {
            if (statement.startsWith("SOURCE ")) result.addAll(expandedStatements(Path.of(statement.substring(7))));
            else result.add(statement);
        }
        return result;
    }

    private static List<String> supportSources(List<String> statements, String table) {
        return statements.stream().filter(sql -> sql.startsWith("INSERT") && sql.contains("INTO " + table)
                && sql.contains("'SUPPORT'")).toList();
    }

    private static List<String> values(String sql, String column) {
        var matcher = Pattern.compile(Pattern.quote(column) + " IN \\(([^)]+)\\)").matcher(sql);
        assertThat(matcher.find()).isTrue();
        return Arrays.stream(matcher.group(1).replace("'", "").split(",")).map(String::trim).toList();
    }

    private static List<String> statements(String filename) throws Exception {
        return statements(Path.of("scripts/rbac-classic-seed", filename));
    }

    private static List<String> statements(Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8)
                .replaceAll("(?m)--[^\\r\\n]*", "");
        return Arrays.stream(sql.split(";")).map(value -> value.replaceAll("\\s+", " ").trim())
                .filter(value -> !value.isEmpty()).toList();
    }
}
