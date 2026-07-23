package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class C5SecurityClosureMigrationContractTest {
    @Test
    void c5SessionsKycConsumptionEventsAndExactConfigAreDurablySeeded() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260719_c5_security_closure.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("session_chain_id")
                .contains("rotated_to_id")
                .contains("rotation_redeemed_at")
                .contains("last_active_at")
                .contains("nx_c5_kyc_reverification_consumption")
                .contains("auth.session.step_up_days")
                .contains("user_c5_session_revoke_one")
                .contains("user_c5_session_revoke_all")
                .contains("user_c5_unlock_short")
                .contains("user_c5_unlock_long")
                .contains("auth.login_locked")
                .contains("auth.refresh_token_reuse_detected")
                .contains("login_key_hash")
                .contains("session_chain_id','id'")
                .contains("owner_domain=VALUES(owner_domain)")
                .contains("c2-high-risk-admin-alert")
                .contains("SHA2(CONCAT('security-password:',u.id),256)")
                .contains("r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT')")
                .contains("VALUES (1,39)");
    }

    @Test
    void cleanSchemaAndClassicSeedsContainTheSameLeastPrivilegeSupportMatrix() throws Exception {
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
        assertThat(grants)
                .contains("r.role_code='SUPPORT'")
                .contains("p.permission_code IN ('user_c5_session_revoke_one','user_c5_session_revoke_all')")
                .contains("p.permission_code='user_c5_unlock_short'");
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
