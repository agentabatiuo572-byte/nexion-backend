package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StartupSchemaMigrationContractTest {
    @Test
    void startupSequenceIncludesEveryCurrentApplicationMigration() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(runner).contains("20260812_commerce_acceptance_sandbox.sql",
                "20260815_h4_wheel_local_sandbox.sql",
                "20260816_team_ambassador_policy.sql",
                "20260817_onboarding_phone_activation.sql",
                "20260817_p2_product_specifications.sql",
                "20260817_notification_preferences.sql",
                "20260817_g1_g7_market_sandbox.sql",
                "20260817_market_app_sandbox_run_scope.sql",
                "20260817_developer_access_governance.sql",
                "20260817_genesis_holder_policy.sql",
                "20260817_h7_voucher_cadence.sql",
                "20260817_legal_terms_versioned.sql",
                "20260818_h7_voucher_cadence_sandbox.sql",
                "20260819_compute_sandbox_reward_run_scope.sql",
                "20260820_e2_task_price_history.sql",
                "20260820_home_grid_datacenter_metadata.sql",
                "20260820_i4_homepage_trust_content.sql",
                "20260821_h2_trial_card_offer.sql",
                "20260823_withdrawal_terminal_refund_projection.sql",
                "20260823_team_hardware_quota_product_alignment.sql",
                "20260826_h2_trial_product_catalog.sql",
                "20260826_trial_conversion_order_backfill.sql");
    }

    @Test
    void migrationsNeverOverrideTheRunnerSelectedDatabase() throws Exception {
        for (Path migration : Files.list(Path.of("scripts/migrations")).toList()) {
            assertThat(Files.readString(migration))
                    .as("migration %s", migration.getFileName())
                    .doesNotContain("USE nexion", "USE `nexion`");
        }
    }

    @Test
    void developmentEnablesFundsAndLoopbackOnlyThroughExplicitEnvironment() throws Exception {
        String profile = Files.readString(Path.of("src/main/resources/application-dev.yml"));
        assertThat(profile).contains("mode: LOCAL_SANDBOX", "allow-loopback-without-country: true");
    }

    @Test
    void productionLauncherDoesNotEnableTheLoopbackNovaAdapterByDefault() throws Exception {
        String launcher = Files.readString(Path.of("scripts/start_ops_console_monolith.ps1"));
        assertThat(launcher).contains(
                "[Nullable[bool]]$EnableLocalNovaAi = $null",
                "$SpringProfile -eq \"dev\"",
                "if ($localNovaAiEnabled) { \"OLLAMA_LOCAL\" } else { \"DISABLED\" }");
    }

    @Test
    void wheelSchemaContractMatchesMigrationGuardsAndIndexes() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        assertThat(schema).contains(
                "chk_growth_wheel_sandbox_scope_source",
                "chk_growth_wheel_sandbox_tier_source",
                "chk_growth_wheel_sandbox_guard_source",
                "chk_growth_wheel_sandbox_ticket_kind",
                "idx_growth_wheel_sandbox_spin_scope",
                "idx_growth_wheel_sandbox_command_scope");
        String migration = Files.readString(Path.of("scripts/migrations/20260815_h4_wheel_local_sandbox.sql"));
        assertThat(migration).contains("Repair tables created by the canonical schema",
                "information_schema.check_constraints", "ALTER TABLE nx_growth_wheel_sandbox_command");
    }

    @Test
    void paymentCardVersionHasAnUpgradeMigration() throws Exception {
        String migrations = Files.readString(Path.of("scripts/migrations/20260810_cd_finance_sandbox.sql"));
        assertThat(migrations).contains("nx_wallet_bank_card", "ADD COLUMN version");
    }

    @Test
    void onboardingSeedMigrationNeverOverwritesOperatorOwnedConfiguration() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260816_onboarding_calibration_authority.sql"));
        assertThat(migration).contains("ON DUPLICATE KEY UPDATE tier=tier",
                "ON DUPLICATE KEY UPDATE config_key=config_key");
        assertThat(migration).doesNotContain("base_rate_usdt=VALUES", "daily_usdt=VALUES", "revision=VALUES");
    }

    @Test
    void onboardingPhoneDevicesAreEnvironmentAndRunScopedInBaselineAndUpgrade() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of("scripts/migrations/20260817_onboarding_phone_activation.sql"));

        assertThat(schema).contains(
                "source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION'",
                "run_id VARCHAR(96) NOT NULL DEFAULT ''",
                "chk_user_device_environment",
                "chk_user_device_scope");
        assertThat(migration).contains(
                "ADD COLUMN source_environment VARCHAR(16)",
                "ADD COLUMN run_id VARCHAR(96)",
                "idx_user_device_scope",
                "chk_user_device_environment",
                "chk_user_device_scope");
    }

    @Test
    void payoutSandboxSchemaIsRunScopedAndRepairsLegacyGlobalKeys() throws Exception {
        String migrations = Files.readString(Path.of("scripts/migrations/20260810_cd_finance_sandbox.sql"));
        assertThat(migrations).contains(
                "run_id VARCHAR(64) NOT NULL",
                "uk_payout_vnd_sandbox_run_order",
                "uk_payout_vnd_sandbox_run_idem",
                "uk_payout_vnd_sandbox_run_event",
                "uk_payout_vnd_sandbox_run_order_ledger",
                "DROP INDEX uk_payout_vnd_sandbox_idem",
                "DROP INDEX uk_payout_vnd_sandbox_event");
    }
}
