package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferralRewardEnvironmentIsolationContractTest {

    @Test
    void productionAndAcceptanceSandboxBatchesUseDifferentFactTablesAndQueries() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));

        String productionSelection = between(source, "SELECT u.id AS invitedUserId", "List<ReferralRow> findPendingReferrals");
        assertThat(productionSelection)
                .contains("#{sourceEnvironment} = 'PRODUCTION'")
                .contains("COALESCE(u.sandbox, 0) = #{accountSandbox}")
                .contains("COALESCE(inviter.sandbox, 0) = #{accountSandbox}")
                .contains("invited_wallet.sandbox = #{accountSandbox}")
                .contains("inviter_wallet.sandbox = #{accountSandbox}")
                .contains("onlyInvitedUserId");

        String productionInsertion = between(source, "INSERT IGNORE INTO nx_referral_reward_settlement", "int insertSettlement");
        assertThat(productionInsertion)
                .contains("#{sourceEnvironment} = 'PRODUCTION'")
                .contains("COALESCE(u.sandbox, 0) = #{accountSandbox}")
                .contains("COALESCE(inviter.sandbox, 0) = #{accountSandbox}")
                .contains("invited_wallet.sandbox = #{accountSandbox}")
                .contains("inviter_wallet.sandbox = #{accountSandbox}");

        String sandboxSelection = between(source, "List<ReferralRow> findPendingReferrals", "List<ReferralRow> findPendingSandboxReferral");
        assertThat(sandboxSelection)
                .contains("nx_h8_sandbox_referral_settlement")
                .contains("COALESCE(u.sandbox, 0) = 1")
                .contains("COALESCE(inviter.sandbox, 0) = 1")
                .doesNotContain("nx_referral_reward_settlement")
                .doesNotContain("nx_admin_risk_");

        String sandboxInsertion = between(source, "INSERT IGNORE INTO nx_h8_sandbox_referral_settlement", "int insertSandboxSettlement");
        assertThat(sandboxInsertion)
                .contains("'mock', 'SANDBOX'")
                .contains("COALESCE(u.sandbox, 0) = 1")
                .contains("COALESCE(inviter.sandbox, 0) = 1")
                .doesNotContain("nx_referral_reward_settlement")
                .doesNotContain("nx_admin_risk_");
    }

    @Test
    void appCountsFilterDeletedInactiveAndOppositeEnvironmentUsers() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));

        String invitedCount = between(source, "FROM nx_user invited", "long appInvitedCount");
        assertThat(invitedCount)
                .contains("invited.is_deleted = 0")
                .contains("invited.status = 'ACTIVE'")
                .contains("#{sourceEnvironment}");

        assertThat(source)
                .contains("long appPositiveSettlementCount(@Param(\"userId\") Long userId,")
                .contains("long appSettlementCount(@Param(\"userId\") Long userId,")
                .contains("@Param(\"sourceEnvironment\") String sourceEnvironment");
    }

    @Test
    void appAccountRequiresWalletAndUserToShareTheSameEnvironment() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));
        String account = between(source, "SELECT u.referral_code AS referralCode", "AppReferralAccount appReferralAccount");

        assertThat(account)
                .contains("JOIN nx_user_wallet w")
                .doesNotContain("LEFT JOIN nx_user_wallet w")
                .contains("COALESCE(w.sandbox, 0) = COALESCE(u.sandbox, 0)");
    }

    @Test
    void isolatedSandboxLedgerIsInBothBaselineSchemaAndControlledMigration() throws Exception {
        String baseline = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260811_h8_acceptance_sandbox_referral_ledger.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        for (String schema : List.of(baseline, migration)) {
            assertThat(schema)
                    .contains("CREATE TABLE IF NOT EXISTS nx_h8_sandbox_referral_settlement")
                    .contains("CREATE TABLE IF NOT EXISTS nx_h8_sandbox_referral_ledger")
                    .contains("source_environment")
                    .contains("source = 'mock' AND source_environment = 'SANDBOX'")
                    .contains("uk_h8_sandbox_referral_run_invited")
                    .contains("uk_h8_sandbox_referral_run_idempotency")
                    .contains("uk_h8_sandbox_referral_ledger_fact");
        }
        assertThat(startup)
                .contains("20260811_h8_acceptance_sandbox_referral_ledger.sql");
    }

    @Test
    void acceptanceSchemaGateUsesTheControlledMapperInsteadOfJdbcTemplate() throws Exception {
        String gate = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/application/H8AcceptanceSandboxSchemaInitializer.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));

        assertThat(gate)
                .contains("@RequiredArgsConstructor")
                .contains("private final ReferralRewardMapper mapper")
                .contains("mapper.h8AcceptanceSandboxSchemaColumns()")
                .doesNotContain("JdbcTemplate");
        assertThat(mapper)
                .contains("int h8AcceptanceSandboxSchemaColumns()");
    }

    @Test
    void sandboxWebAndSchemaBeansUseTheStrictSingleProfileCondition() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/web/AcceptanceSandboxReferralRewardController.java"));
        String initializer = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/application/H8AcceptanceSandboxSchemaInitializer.java"));

        assertThat(controller)
                .contains("@Conditional(H8AcceptanceSandboxProfileCondition.class)")
                .doesNotContain("@Profile(");
        assertThat(initializer)
                .contains("@Conditional(H8AcceptanceSandboxProfileCondition.class)")
                .doesNotContain("@Profile(");
    }

    @Test
    void mockCleanupSoftDeletesOnlyTheDedicatedH8SandboxFactsForMockUsers() throws Exception {
        String cleanup = Files.readString(Path.of("scripts/cleanup-ops-mock-data.sql"));
        assertThat(cleanup)
                .contains("UPDATE nx_h8_sandbox_referral_settlement")
                .contains("UPDATE nx_h8_sandbox_referral_ledger")
                .contains("tmp_ops_mock_user");
    }

    @Test
    void sandboxSettlementNeverUsesSharedSettlementEarningsRiskOutboxOrWalletLedger() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));
        String referral = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/application/OpsReferralRewardService.java"));

        String sandboxService = between(referral, "private Map<String, Object> settleSandbox", "private Map<String, Object> settle(");
        assertThat(sandboxService)
                .contains("insertSandboxSettlement")
                .contains("insertSandboxLedger")
                .doesNotContain("earningsReleaseService")
                .doesNotContain("outbox.publish")
                .doesNotContain("ledger.postLedgerEntry")
                .doesNotContain("creditSandboxWallet")
                .doesNotContain("nx_user_wallet")
                .doesNotContain("findPendingReferrals");

        String sandboxLedgerInsert = between(source, "INSERT INTO nx_h8_sandbox_referral_ledger", "int insertSandboxLedger");
        assertThat(sandboxLedgerInsert)
                .contains("source_environment")
                .contains("'SANDBOX'")
                .contains("nx_h8_sandbox_referral_ledger")
                .doesNotContain("nx_user_wallet")
                .doesNotContain("INSERT INTO nx_wallet_ledger");

        String appProjection = between(source, "AppReferralLedgerSummary appVerifiedSandboxRewardSummary", "List<AppReferralLedgerRow> appRecentVerifiedSandboxRewards");
        assertThat(appProjection)
                .contains("nx_h8_sandbox_referral_settlement")
                .contains("nx_h8_sandbox_referral_ledger")
                .doesNotContain("nx_referral_reward_settlement")
                .doesNotContain("nx_wallet_ledger")
                .doesNotContain("nx_earnings_release_entry");
    }

    @Test
    void joinedRecentSettlementProjectionQualifiesEverySettlementColumn() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));
        String recent = between(source,
                "SELECT s.settlement_no AS settlementNo, s.invited_user_id AS invitedUserId",
                "List<Map<String, Object>> recentSettlements");

        assertThat(recent)
                .contains("s.status AS status")
                .contains("s.created_at AS createdAt")
                .doesNotContain(" inviter_nex AS inviterNex, status")
                .doesNotContain(" status, created_at AS createdAt");
    }

    private String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertThat(from).as("start marker %s", start).isGreaterThanOrEqualTo(0);
        assertThat(to).as("end marker %s", end).isGreaterThan(from);
        return source.substring(from, to);
    }
}
