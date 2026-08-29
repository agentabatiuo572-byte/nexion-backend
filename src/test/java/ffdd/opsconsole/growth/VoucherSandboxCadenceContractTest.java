package ffdd.opsconsole.growth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VoucherSandboxCadenceContractTest {
    @Test
    void historicalCadenceFixtureIsTestOnlyAndNotInstalledAtStartup() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260818_h7_voucher_cadence_sandbox.sql"));
        String startupRunner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/AppGrowthEngagementMapper.java"));
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/application/AppGrowthEngagementService.java"));

        assertThat(migration).contains("nx_voucher_popup_sandbox_state", "run_id", "user_id", "voucher_id",
                "claim_status", "claim_id", "claim_idempotency_key",
                "UNIQUE KEY uk_voucher_popup_sandbox_claim_idem");
        assertThat(startupRunner).doesNotContain("20260818_h7_voucher_cadence_sandbox.sql");
        assertThat(mapper).contains("voucherStateSandbox", "run_id=#{runId}", "markVoucherPopupSeenSandbox",
                "lockSandboxClaimableVoucher", "insertSandboxVoucherClaim", "claimExistingSandboxVoucher");
        assertThat(service).contains("voucherSandboxService.isPresent()")
                .contains("voucherSandboxService.get().voucherState(userId, requestedRunId)")
                .contains("voucherSandboxService.get().markVoucherPopupSeen(userId, voucherId, requestedRunId)")
                .contains("userId, voucherId, surface, idempotencyKey, requestedRunId");
    }

    @Test
    void existingSandboxStateGetsEveryClaimColumnAndIndexThroughIdempotentGuards() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260818_h7_voucher_cadence_sandbox.sql"));

        assertThat(migration).contains(
                "ADD COLUMN claim_status",
                "ADD COLUMN claim_id",
                "ADD COLUMN claim_idempotency_key",
                "information_schema.STATISTICS",
                "INDEX_NAME='uk_voucher_popup_sandbox_claim_idem'",
                "ADD UNIQUE KEY uk_voucher_popup_sandbox_claim_idem");
    }
}
