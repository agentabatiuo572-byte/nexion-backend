package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class C5SecurityClosureMigrationContractTest {
    @Test
    void cleanSchemaDefinesC5AuthoritiesAndClassicSeedKeepsSupportInItsServiceDomain() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);
        String permissions = Files.readString(
                Path.of("scripts/rbac-classic-seed/C.sql"), StandardCharsets.UTF_8);
        String grants = Files.readString(
                Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"), StandardCharsets.UTF_8);

        assertThat(schema).contains(
                "user_c5_session_revoke_one",
                "user_c5_session_revoke_all",
                "user_c5_unlock_short",
                "user_c5_unlock_long");
        assertThat(permissions).contains(
                "user_c5_session_revoke_one",
                "user_c5_session_revoke_all",
                "user_c5_unlock_short",
                "user_c5_unlock_long");
        var supportGrants = Arrays.stream(grants.replaceAll("(?m)--[^\\r\\n]*", "").split(";"))
                .map(statement -> statement.replaceAll("\\s+", " ").trim())
                .filter(statement -> statement.startsWith("INSERT") && statement.contains("'SUPPORT'")).toList();
        assertThat(supportGrants).hasSize(1);
        assertThat(supportGrants.get(0)).contains("WHERE r.role_code='SUPPORT'",
                "p.permission_code IN ('service_m1_read','service_m1_write','service_m2_read','service_m2_write', "
                        + "'service_m3_read','service_m3_write','service_m4_read','service_m5_read')",
                "p.status=1 AND p.is_deleted=0").doesNotContain("user_c5_", "LIKE", "service_m5_write");
    }

    @Test
    void liveRepairDurablyGrantsOnlyTheDocumentedSupportSessionRevokePermissions() throws Exception {
        String repair = Files.readString(
                Path.of("scripts/migrations/20260720_c5_support_session_revoke_grant.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(repair)
                .contains("r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT')")
                .contains("p.permission_code IN ('user_c5_session_revoke_one','user_c5_session_revoke_all')")
                .doesNotContain("'user_c5_write'");
    }
}
