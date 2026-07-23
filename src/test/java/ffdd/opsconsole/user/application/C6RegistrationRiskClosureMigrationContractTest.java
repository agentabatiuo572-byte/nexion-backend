package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class C6RegistrationRiskClosureMigrationContractTest {
    @Test
    void migrationAndCleanSchemaSeedTheVersionAndCaptchaSerializationRows() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260719_c6_registration_risk_closure.sql"),
                StandardCharsets.UTF_8);
        String schema = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("auth.risk.c6.version")
                .contains("auth.risk.captcha_off_window")
                .contains("Retired by C6 fact-derived statistics closure");
        assertThat(schema)
                .contains("auth.risk.c6.version")
                .contains("auth.risk.captcha_off_window");
    }

    @Test
    void growthReceivesC6MenuAndReadPermissionButNeverWritePermission() throws Exception {
        String menuSeed = Files.readString(
                Path.of("scripts/rbac-classic-seed/01-menu-seed.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String rolePermissionSeed = Files.readString(
                Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String repairMigration = Files.readString(
                Path.of("scripts/migrations/20260720_c6_growth_read_grant.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(menuSeed)
                .contains("m.menu_code IN ('C','C1') WHERE r.role_code IN ('FINANCE','GROWTH')")
                .contains("m.menu_code='C6' WHERE r.role_code='GROWTH'");
        assertThat(rolePermissionSeed)
                .contains("WHERE r.role_code='GROWTH' AND p.permission_code='user_c6_read'");
        assertThat(repairMigration)
                .contains("r.role_code='GROWTH'")
                .contains("m.menu_code IN ('C','C1','C6')")
                .contains("p.permission_code='user_c6_read'")
                .doesNotContain("user_c6_write");
    }

    @Test
    void c6RiskEventsUseTheA4RiskFamilyAndRepairLegacyFacts() throws Exception {
        String c4Migration = Files.readString(
                Path.of("scripts/migrations/20260719_c4_kyc_closure.sql"),
                StandardCharsets.UTF_8);
        String c5Migration = Files.readString(
                Path.of("scripts/migrations/20260719_c5_security_closure.sql"),
                StandardCharsets.UTF_8);
        String repairMigration = Files.readString(
                Path.of("scripts/migrations/20260720_c6_a4_risk_family_repair.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(c4Migration)
                .contains("'risk.kyc_review_triggered','risk','risk'")
                .doesNotContain("phase_risk");
        assertThat(c5Migration)
                .contains("'auth.login_locked','auth','risk'")
                .contains("'auth.refresh_token_reuse_detected','auth','risk'")
                .doesNotContain("phase_risk");
        assertThat(repairMigration)
                .contains("UPDATE nx_event_schema_registry")
                .contains("UPDATE nx_event_outbox")
                .contains("SET family_key='risk'")
                .contains("WHERE family_key='phase_risk'");
    }
}
