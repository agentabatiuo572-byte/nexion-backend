package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxRetirementMigrationContractTest {
    private static final Path SCRIPT = Path.of("scripts", "maintenance", "retire_sandbox_to_development.sql");
    private static final Path RUNNER = Path.of("scripts", "retire_sandbox_to_development.ps1");

    @Test
    void migrationArchivesThenUsesAnExplicitFailClosedClassification() throws IOException {
        assertThat(SCRIPT).exists();
        String sql = Files.readString(SCRIPT);

        assertThat(sql).contains(
                "GET_LOCK('nexion:sandbox-retirement:v2'",
                "nexion_development_archive_20260828",
                "sandbox_retirement_classification",
                "sandbox_retirement_wallet_reset_item",
                "canonical_ledger_cutoff_at",
                "PROMOTE_ACCOUNT_IDENTITY",
                "RESET_WALLET_SCAFFOLD",
                "ARCHIVE_ONLY_DELETE",
                "ARCHIVE_ONLY_DROP_TABLE",
                "w.usdt_available=0",
                "w.nex_available=0",
                "w.pending_withdraw=0",
                "w.lifetime_earned=0",
                "w.cumulative_deposit_usdt=0",
                "SELECT COUNT(*) INTO @src_count",
                "VALUES('sandbox-to-development-v2-classified',v_table,v_predicate,@src_count,@arc_count",
                "RECONCILED_POST_RESET_LEDGER",
                "SANDBOX_WALLET_RESET_ITEM_PROOF_MISSING",
                "DROP INDEX uk_user_phone_sandbox",
                "ADD UNIQUE INDEX uk_user_phone",
                "SANDBOX_QUARANTINE_ROW_REMAINS",
                "'IN_PROGRESS'",
                "status='FAILED'",
                "RELEASE_LOCK('nexion:sandbox-retirement:v2')");
        assertThat(sql)
                .doesNotContain("SET source_environment = ''PRODUCTION''")
                .doesNotContain("SET source_environment='PRODUCTION'")
                .doesNotContain("UPDATE nx_user_wallet SET sandbox = 0")
                .doesNotContain("ROLLBACK;");
        assertThat(sql.indexOf("nexion_development_archive_20260828"))
                .isLessThan(sql.indexOf("PROMOTE_ACCOUNT_IDENTITY"));
    }

    @Test
    void legacyAggregateProofCanOnlyBeUpgradedByExactPostResetLedgerReconciliation() throws IOException {
        String reconciliation = Files.readString(Path.of(
                "scripts", "maintenance", "reconcile_legacy_sandbox_wallet_reset_proof.sql"));

        assertThat(reconciliation).contains(
                "This does not change any active wallet",
                "w.usdt_available<>COALESCE(ledger.usdt_net,0)",
                "w.nex_available<>COALESCE(ledger.nex_net,0)",
                "w.lifetime_earned<>COALESCE(ledger.compute_earned,0)",
                "LEGACY_WALLET_RESET_LEDGER_RECONCILIATION_FAILED",
                "RECONCILED_POST_RESET_LEDGER",
                "LEGACY_WALLET_RESET_ITEM_COUNT_MISMATCH");
        assertThat(reconciliation).doesNotContain(
                "UPDATE nexion.nx_user_wallet",
                "DELETE FROM nexion.nx_user_wallet");
    }

    @Test
    void runnerRequiresExplicitConfirmationAndLoopbackDevelopmentDatabase() throws IOException {
        assertThat(RUNNER).exists();
        String runner = Files.readString(RUNNER);

        assertThat(runner).contains(
                "RETIRE_SANDBOX_TO_DEVELOPMENT",
                "127.0.0.1",
                "localhost",
                "Database -ne \"nexion\"",
                "--show-warnings");
    }

    @Test
    void baselineAndStartupMigrationCannotRecreateTheRetiredPhoneEnvironmentIndex() throws IOException {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260812_auth_environment_identity_namespace.sql"));

        assertThat(schema).contains("UNIQUE KEY uk_user_phone (country_code, phone)")
                .doesNotContain("UNIQUE KEY uk_user_phone_sandbox");
        assertThat(migration)
                .contains("DROP INDEX uk_user_phone_sandbox")
                .contains("ADD UNIQUE KEY uk_user_phone (country_code,phone)")
                .doesNotContain("ADD UNIQUE KEY uk_user_phone_sandbox");
    }

    @Test
    void normalStartupCannotReplayAnyRetiredRailMigration() throws IOException {
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(startup)
                .contains("20260828_cd_finance_canonical.sql", "retiredRailMigrations")
                .doesNotContain(
                        "20260819_compute_sandbox_reward_run_scope.sql",
                        "20260810_cd_finance_sandbox.sql",
                        "20260816_oauth_sandbox_event_schema.sql",
                        "20260811_funds_persistent_sandbox.sql",
                        "20260811_g2_acceptance_sandbox.sql",
                        "20260812_commerce_acceptance_sandbox.sql",
                        "20260812_learning_acceptance_sandbox.sql",
                        "20260812_support_acceptance_sandbox.sql",
                        "20260815_h4_wheel_local_sandbox.sql",
                        "20260816_growth_quest_sandbox.sql",
                        "20260816_payout_address_sandbox.sql",
                        "20260817_g1_g7_market_sandbox.sql",
                        "20260817_market_app_sandbox_run_scope.sql",
                        "20260818_h7_voucher_cadence_sandbox.sql");

        String schema = Files.readString(Path.of("scripts/schema.sql"));
        assertThat(schema.lastIndexOf("DROP TABLE IF EXISTS"))
                .isGreaterThan(schema.lastIndexOf("CREATE TABLE IF NOT EXISTS nx_growth_quest_sandbox"));
    }

    @Test
    void deployableDevelopmentRuntimeCannotRegisterRetiredSandboxRoutes() throws IOException {
        for (String source : List.of(
                "auth/web/OAuthSandboxChallengeController.java",
                "bi/web/OpsBehaviorAnalyticsAcceptanceController.java",
                "commerce/web/CommerceAcceptanceSandboxController.java",
                "content/web/OpsLearningAcceptanceCatalogController.java",
                "content/web/OpsLearningAcceptanceObservationController.java",
                "content/web/OpsSupportAcceptanceSandboxController.java",
                "content/web/SupportAcceptanceSandboxController.java",
                "finance/web/CregisSandboxController.java",
                "finance/web/FundsSandboxController.java",
                "finance/web/OpsPayoutVndSandboxController.java",
                "growth/web/AcceptanceSandboxReferralRewardController.java",
                "janus/web/AppJanusSandboxEnrollmentController.java",
                "market/web/AppGenesisSandboxFixtureController.java",
                "market/web/G2AcceptanceSandboxController.java",
                "team/web/AppProofSandboxFixtureController.java")) {
            String controller = Files.readString(Path.of("src/main/java/ffdd/opsconsole", source));
            assertThat(controller).as(source).contains("@Profile(\"test\")");
        }

        String developmentPasskey = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/web/OAuthDevelopmentPasskeyController.java"));
        assertThat(developmentPasskey)
                .contains("@Profile(\"dev\")")
                .contains("/oauth/development/passkey/challenge")
                .doesNotContain("/oauth/sandbox/challenge");

        for (String source : List.of(
                "market/web/AppGenesisController.java",
                "team/web/AppProofController.java",
                "auth/web/AppUserAuthController.java",
                "finance/web/OpsPayoutVndController.java",
                "growth/web/OpsGrowthController.java",
                "growth/web/AppGrowthEngagementController.java")) {
            String controller = Files.readString(Path.of("src/main/java/ffdd/opsconsole", source));
            assertThat(controller.toLowerCase()).as(source)
                    .doesNotContain("sandbox", "acceptance");
        }

        String delivery = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/risk/application/B5RiskAlertDeliveryService.java"));
        assertThat(delivery)
                .contains("case \"email\" -> throw new IllegalStateException(\"B5_EMAIL_PROVIDER_UNAVAILABLE\")")
                .doesNotContain("deliverSandboxEmail", "sandboxProfileAllowed", "sandbox:b5-email");
        String team = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/application/OpsTeamService.java"));
        assertThat(team).doesNotContain(
                "F2SandboxCoveragePolicy", "sandboxOverrideEnabled", "f2SandboxProvenance");
        assertThat(Path.of(
                "src/main/java/ffdd/opsconsole/team/application/F2SandboxCoveragePolicy.java"))
                .doesNotExist();
    }

    @Test
    void canonicalDevelopmentPasskeyAuditSchemaIsAppliedAtStartup() throws IOException {
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260828_development_passkey_event_schema.sql"));

        assertThat(startup).contains("20260828_development_passkey_event_schema.sql");
        assertThat(migration)
                .contains("auth.development_passkey_login")
                .contains("'migration:development-passkey'")
                .doesNotContain("source_environment='SANDBOX'");
    }
}
