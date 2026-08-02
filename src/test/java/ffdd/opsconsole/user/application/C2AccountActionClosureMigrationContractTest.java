package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class C2AccountActionClosureMigrationContractTest {
    @Test
    void c2RoleMatrixReversibleFinanceLinkAndCanonicalEventsAreDurablySeeded() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260718_c2_account_action_closure.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("c2_previous_status")
                .contains("c2_frozen_by_user_status")
                .contains("admin.user_frozen")
                .contains("admin.user_unfrozen")
                .contains("admin.user_impersonation_started")
                .contains("admin.user_impersonation_ended")
                .contains("target_user_id")
                .contains("duration_sec")
                .contains("c2-high-risk-admin-alert")
                .contains("VALUES (1, 28)");
    }

    @Test
    void accountListEventsAreRegisteredBeforeC2MutationsCanPublishThem() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260729_c2_account_list_event_schema.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("'admin.account_list_upserted'")
                .contains("'admin.account_list_removed'")
                .contains("'phase_admin'")
                .contains("'ACTIVE'")
                .contains("'kind'")
                .contains("'reason'")
                .contains("'idempotency_key'")
                .contains("'expires_at'")
                .contains("'sessions_revoked'")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("p.is_deleted=1")
                .contains("INSERT INTO nx_event_schema_revision");
    }

    @Test
    void legacyC2RunnerRemainsAnIdempotentStandaloneMigrationTool() throws Exception {
        String runner = Files.readString(
                Path.of("scripts/apply_c2_account_list_event_schema.ps1"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertThat(runner)
                .contains("SupportsShouldProcess = $true")
                .contains("20260729_c2_account_list_event_schema.sql")
                .contains("--default-character-set=utf8mb4")
                .contains("$migration.Replace('\\', '/')")
                .contains("Backend startup has been stopped");
    }

    @Test
    void startupRunnerAppliesA1CasAndC2SchemasWithoutPuttingPasswordsOnTheCommandLine() throws Exception {
        String a1Migration = Files.readString(
                Path.of("scripts/migrations/20260729_a1_admin_account_status_cas.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String runner = Files.readString(
                Path.of("scripts/apply_startup_schema_migrations.ps1"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String starter = Files.readString(
                Path.of("scripts/start_ops_console_monolith.ps1"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(a1Migration)
                .contains("TABLE_NAME = 'nx_admin'")
                .contains("COLUMN_NAME = 'version'")
                .contains("BIGINT UNSIGNED NOT NULL DEFAULT 0");
        assertThat(runner)
                .contains("SupportsShouldProcess = $true")
                .contains("ConfirmImpact = 'High'")
                .contains("20260729_a1_admin_account_status_cas.sql")
                .contains("20260729_c2_account_list_event_schema.sql")
                .contains("$env:MYSQL_PWD")
                .contains("$WhatIfPreference")
                .doesNotContain("-p$Password")
                .doesNotContain("-p<mysql-password>");
        assertThat(starter)
                .contains("apply_startup_schema_migrations.ps1")
                .contains("-Confirm:$false")
                .doesNotContain("SkipC2AccountListEventSchemaMigration");
        assertThat(readme)
                .contains("apply_startup_schema_migrations.ps1")
                .contains("MYSQL_PWD")
                .doesNotContain("-p<mysql-password>");
    }
}
