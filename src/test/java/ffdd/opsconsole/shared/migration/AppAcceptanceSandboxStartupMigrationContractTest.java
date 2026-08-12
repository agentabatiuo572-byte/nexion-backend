package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppAcceptanceSandboxStartupMigrationContractTest {
    @Test
    void everyAppAcceptanceSandboxMigrationIsInControlledStartupAndBaseline() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String analytics = Files.readString(Path.of("scripts/migrations/20260812_l6_acceptance_sandbox_fact.sql"));
        String commerce = Files.readString(Path.of("scripts/migrations/20260812_commerce_acceptance_sandbox.sql"));
        String learning = Files.readString(Path.of("scripts/migrations/20260812_learning_acceptance_sandbox.sql"));
        String support = Files.readString(Path.of("scripts/migrations/20260812_support_acceptance_sandbox.sql"));
        String h8RunScope = Files.readString(Path.of("scripts/migrations/20260812_h8_acceptance_sandbox_run_scope.sql"));

        assertThat(runner).contains("20260812_l6_acceptance_sandbox_fact.sql")
                .contains("20260812_commerce_acceptance_sandbox.sql")
                .contains("20260812_learning_acceptance_sandbox.sql")
                .contains("20260812_support_acceptance_sandbox.sql")
                .contains("20260812_h8_acceptance_sandbox_run_scope.sql");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS nx_behavior_sandbox_fact")
                .contains("CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_catalog")
                .contains("CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_order")
                .contains("CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_callback_inbox")
                .contains("CREATE TABLE IF NOT EXISTS nx_learning_sandbox_progress")
                .contains("CREATE TABLE IF NOT EXISTS nx_learning_sandbox_idempotency")
                .contains("CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_ticket")
                .contains("CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_idempotency");
        String analyticsBaseline = schema.substring(
                schema.indexOf("CREATE TABLE IF NOT EXISTS nx_behavior_sandbox_fact"),
                schema.indexOf("CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_catalog"));
        assertThat(analyticsBaseline).containsOnlyOnce("source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX'")
                .doesNotContain("source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION'");
        assertThat(analytics).contains("nx_behavior_sandbox_fact", "uk_behavior_sandbox_client_event_id");
        assertThat(commerce).contains("nx_commerce_sandbox_catalog", "nx_commerce_sandbox_order", "nx_commerce_sandbox_callback_inbox")
                .doesNotContain("nx_commerce_sandbox_wallet", "nx_commerce_sandbox_bill");
        assertThat(learning).contains("nx_learning_sandbox_progress", "nx_learning_sandbox_idempotency")
                .doesNotContain("CREATE TABLE IF NOT EXISTS nx_learning_progress", "INSERT INTO nx_earnings_release_entry");
        assertThat(support).contains("nx_support_acceptance_sandbox_ticket", "nx_support_acceptance_sandbox_idempotency")
                .doesNotContain("CREATE TABLE IF NOT EXISTS nx_support_ticket", "CREATE TABLE IF NOT EXISTS nx_conversation");
        assertThat(h8RunScope).contains("information_schema.COLUMNS", "COLUMN_NAME='run_id'",
                        "uk_h8_sandbox_referral_run_invited")
                .contains("idx_h8_sandbox_referral_ledger_run_user_time")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
        assertThat(analytics).doesNotContain("ADD COLUMN IF NOT EXISTS");
        assertThat(learning).doesNotContain("ADD COLUMN IF NOT EXISTS");
        assertThat(schema).contains("uk_h8_sandbox_referral_run_invited (run_id, invited_user_id)")
                .contains("uk_h8_sandbox_referral_ledger_fact (run_id, settlement_no, user_id, asset)");
    }

    @Test
    void readmeDoesNotPresentBaselineSchemaAsACompleteAcceptanceInstall() throws Exception {
        String readme = Files.readString(Path.of("README.md"));

        assertThat(readme)
                .contains("scripts/schema.sql is only the baseline schema")
                .contains("apply_startup_schema_migrations.ps1 is the canonical installer")
                .contains("schema.sql plus seed.sql is not a complete acceptance database");
    }
}
