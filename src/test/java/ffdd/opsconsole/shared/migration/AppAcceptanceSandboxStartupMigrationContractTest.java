package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppAcceptanceSandboxStartupMigrationContractTest {
    @Test
    void mergedStartupPreservesPromotionAndPaymentCapturePrerequisitesInOrder() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        for (String migration : java.util.List.of("20261007_growth_promotions.sql",
                "20261007_growth_promotions_order_receipt.sql", "20261007_earnings_source_recovery.sql",
                "20261007_growth_promotions_list_snapshot.sql", "20261007_growth_promotions_quota_restore.sql",
                "20261007_e4_wallet_bill_prerequisite.sql", "20261007_support_groups.sql",
                "20261009_e4_wallet_bill_schema_precision_forward.sql", "20261008_support_payment_attribution.sql",
                "20261008_support_payment_history_birth.sql")) {
            assertThat(runner).containsOnlyOnce(migration);
        }
        assertThat(runner.indexOf("20261007_growth_promotions.sql"))
                .isLessThan(runner.indexOf("20261007_growth_promotions_order_receipt.sql"));
        assertThat(runner.indexOf("20261007_growth_promotions_order_receipt.sql"))
                .isLessThan(runner.indexOf("20261007_growth_promotions_quota_restore.sql"));
        assertThat(runner.indexOf("20261007_e4_wallet_bill_prerequisite.sql"))
                .isLessThan(runner.indexOf("20261009_e4_wallet_bill_schema_precision_forward.sql"));
        assertThat(runner.indexOf("20261007_support_groups.sql"))
                .isLessThan(runner.indexOf("20261009_e4_wallet_bill_schema_precision_forward.sql"));
        assertThat(runner).doesNotContain("20261009_e4_wallet_bill_schema.sql");
        assertThat(runner.indexOf("20261009_e4_wallet_bill_schema_precision_forward.sql"))
                .isLessThan(runner.indexOf("20261008_support_payment_attribution.sql"));
        assertThat(runner.indexOf("20261008_support_payment_attribution.sql"))
                .isLessThan(runner.indexOf("20261008_support_payment_history_birth.sql"));
    }

    @Test
    void promotionRewardPagesAreRegisteredDuringCanonicalStartup() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String routes = Files.readString(Path.of("scripts/migrations/20261008_l6_promotion_reward_routes.sql"));
        assertThat(runner).containsOnlyOnce("20261008_l6_promotion_reward_routes.sql");
        assertThat(routes).contains("/pages/events/promotion-rewards", "/pages/events/promotion-reward-detail",
                "ON DUPLICATE KEY UPDATE", "tracked=1", "is_deleted=0");
    }

    @Test
    void historicalFixturesRemainReadableButCannotRunAtCanonicalStartup() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String analytics = Files.readString(Path.of("scripts/migrations/20260812_l6_acceptance_sandbox_fact.sql"));
        String commerce = Files.readString(Path.of("scripts/migrations/20260812_commerce_acceptance_sandbox.sql"));
        String learning = Files.readString(Path.of("scripts/migrations/20260812_learning_acceptance_sandbox.sql"));
        String support = Files.readString(Path.of("scripts/migrations/20260812_support_acceptance_sandbox.sql"));
        String h8RunScope = Files.readString(Path.of("scripts/migrations/20260812_h8_acceptance_sandbox_run_scope.sql"));

        assertThat(runner).doesNotContain("20260812_l6_acceptance_sandbox_fact.sql")
                .doesNotContain("20260812_commerce_acceptance_sandbox.sql")
                .doesNotContain("20260812_learning_acceptance_sandbox.sql")
                .doesNotContain("20260812_support_acceptance_sandbox.sql")
                .doesNotContain("20260812_h8_acceptance_sandbox_run_scope.sql");
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
                .contains("uk_h8_sandbox_referral_ledger_fact (run_id, settlement_no, user_id, asset)")
                .contains("DROP TABLE IF EXISTS")
                .contains("nx_behavior_sandbox_fact,")
                .contains("nx_commerce_sandbox_catalog,")
                .contains("nx_learning_sandbox_progress,")
                .contains("nx_support_acceptance_sandbox_run,");
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
