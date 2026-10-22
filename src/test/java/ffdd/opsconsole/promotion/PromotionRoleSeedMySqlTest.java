package ffdd.opsconsole.promotion;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named = "GROWTH_PROMOTION_RUNTIME", matches = "1")
class PromotionRoleSeedMySqlTest {
    @Test
    void superAdminReceivesPromotionCapabilitiesWithoutSpreadingOrdinaryRoleGrants() throws Exception {
        var json = new ObjectMapper();
        var config = json.readTree(Files.readString(Path.of(
                "C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/mysql/connection.private.json")));
        String database = "growth_role_seed_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(database.matches("growth_role_seed_[0-9a-f]{32}"));
        try (var connection = DriverManager.getConnection(config.path("url").asText(),
                config.path("username").asText(), config.path("password").asText());
             var statement = connection.createStatement()) {
            try (var server = statement.executeQuery("SELECT DATABASE(), @@port")) {
                assertTrue(server.next());
                assertEquals("growth_promotions_20261007", server.getString(1));
                assertEquals(33339, server.getInt(2));
            }
            statement.execute("CREATE DATABASE " + database);
            for (String table : List.of("nx_admin_role", "nx_admin_permission", "nx_admin_role_permission")) {
                statement.execute("CREATE TABLE " + database + "." + table
                        + " LIKE growth_promotions_20261007." + table);
            }
            statement.execute("USE " + database);
            for (String role : List.of("SUPER_ADMIN", "AUDITOR", "GROWTH", "FINANCE", "FINANCE_LEAD")) {
                statement.execute("INSERT INTO nx_admin_role(role_code,role_name,status,is_deleted) VALUES('"
                        + role + "','" + role + "',1,0)");
            }
            var promotionPermissions = Pattern.compile("\\('(?<code>growth_promotion_[a-z_]+)'")
                    .matcher(Files.readString(Path.of("scripts/migrations/20261007_growth_promotions.sql")))
                    .results().map(match -> match.group(1)).distinct().sorted().toList();
            assertEquals(20, promotionPermissions.size());
            var fixturePermissions = new ArrayList<>(promotionPermissions);
            fixturePermissions.addAll(List.of("growth_h4_read", "growth_promotion_disabled", "growth_promotion_deleted"));
            for (String permission : fixturePermissions) {
                statement.execute("INSERT INTO nx_admin_permission(permission_code,permission_name,resource_type,perm_type,status,is_deleted) VALUES('"
                        + permission + "','" + permission + "','API','READ',1,0)");
            }
            statement.execute("UPDATE nx_admin_permission SET status=0 WHERE permission_code='growth_promotion_disabled'");
            statement.execute("UPDATE nx_admin_permission SET is_deleted=1 WHERE permission_code='growth_promotion_deleted'");
            // An explicitly granted FINANCE capability must not spread through the
            // old FINANCE_LEAD inheritance path, or disappear during seed reruns.
            grant(statement, "FINANCE", "growth_promotion_read");
            grant(statement, "FINANCE", "growth_promotion_publish");
            statement.execute("UPDATE nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id SET rp.is_deleted=1 WHERE r.role_code='FINANCE' AND p.permission_code='growth_promotion_publish'");
            grant(statement, "SUPER_ADMIN", "growth_promotion_read");
            statement.execute("UPDATE nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id SET rp.is_deleted=1 WHERE r.role_code='SUPER_ADMIN'");
            var expected = new ArrayList<String>();
            expected.add("FINANCE:growth_promotion_read");
            promotionPermissions.forEach(permission -> expected.add("SUPER_ADMIN:" + permission));
            statement.execute("UPDATE nx_admin_role SET status=0 WHERE role_code='SUPER_ADMIN'");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/20261022_04_h4_superadmin_permissions.sql"));
            assertEquals(List.of("FINANCE:growth_promotion_read"), promotionGrants(statement));
            statement.execute("UPDATE nx_admin_role SET status=1,is_deleted=1 WHERE role_code='SUPER_ADMIN'");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/20261022_04_h4_superadmin_permissions.sql"));
            assertEquals(List.of("FINANCE:growth_promotion_read"), promotionGrants(statement));
            statement.execute("UPDATE nx_admin_role SET is_deleted=0 WHERE role_code='SUPER_ADMIN'");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/20261022_04_h4_superadmin_permissions.sql"));
            assertEquals(expected, promotionGrants(statement));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/20261022_04_h4_superadmin_permissions.sql"));
            assertEquals(expected, promotionGrants(statement));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/rbac-classic-seed/02-role-permission-seed.sql"));
            assertEquals(expected, promotionGrants(statement));
            try (var old = statement.executeQuery("SELECT COUNT(*) FROM nx_admin_role_permission rp JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE p.permission_code='growth_h4_read' AND rp.is_deleted=0")) {
                assertTrue(old.next()); assertEquals(3, old.getInt(1));
            }
            grant(statement, "GROWTH", "growth_promotion_publish");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/rbac-classic-seed/02-role-permission-seed.sql"));
            expected.add("GROWTH:growth_promotion_publish");
            expected.sort(String::compareTo);
            assertEquals(expected, promotionGrants(statement));
            Files.writeString(Path.of("target/promotion-role-seed-runtime.json"), json.writeValueAsString(Map.of(
                    "status", "PASS", "database", database, "seedExecutions", 2,
                    "implicitOrdinaryRoleGrants", 0, "superAdminPromotionGrants", 20, "preservedExplicitGrants", promotionGrants(statement))));
            // Only the freshly created, strictly named fixture is removed on success.
            // Leave a failed fixture intact for diagnosis.
            statement.execute("USE growth_promotions_20261007");
            statement.execute("DROP DATABASE " + database);
        }
    }
    private static void grant(Statement statement, String role, String permission) throws SQLException {
        statement.execute("INSERT INTO nx_admin_role_permission(role_id,permission_id) SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p WHERE r.role_code='"
                + role + "' AND p.permission_code='" + permission + "'");
    }
    private static List<String> promotionGrants(Statement statement) throws SQLException {
        var grants = new ArrayList<String>();
        try (var rows = statement.executeQuery("SELECT CONCAT(r.role_code,':',p.permission_code) FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE LEFT(p.permission_code,17)='growth_promotion_' AND rp.is_deleted=0 ORDER BY r.role_code,p.permission_code")) {
            while (rows.next()) grants.add(rows.getString(1));
        }
        return grants;
    }
}
