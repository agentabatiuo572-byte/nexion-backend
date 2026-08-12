package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommerceAcceptanceSandboxIsolationContractTest {
    @Test
    void sandboxSettlementNeverUsesTheProductionWalletOrLedger() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/application/CommerceAcceptanceSandboxService.java"))
                + Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"))
                + Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/mapper/FundsSandboxMapper.java"));
        assertThat(source).contains("nx_funds_sandbox_wallet", "nx_funds_sandbox_ledger", "nx_commerce_sandbox_callback_inbox")
                .doesNotContain("nx_user_wallet").doesNotContain("nx_wallet_ledger");
    }

    @Test
    void migrationHasReplayAndSourceEnvironmentInvariantsWithoutStartupRegistration() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260812_commerce_acceptance_sandbox.sql"));
        assertThat(migration).contains("uk_commerce_sandbox_callback_event", "source = 'mock' AND source_environment = 'SANDBOX'")
                .contains("canonical_status", "result_version", "wallet_after")
                .doesNotContain("nx_commerce_sandbox_wallet").doesNotContain("nx_commerce_sandbox_bill")
                .doesNotContain("nx_user_wallet").doesNotContain("nx_wallet_ledger");
    }

    @Test
    void settlementNeverTransitionsOrRestocksCanonicalOrderOrProductFacts() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/application/CommerceAcceptanceSandboxService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(service).doesNotContain("transitionOrder", "restockProduct", "decrementProductStock");
        assertThat(mapper).doesNotContain("UPDATE nx_order", "UPDATE nx_product", "INSERT INTO nx_order", "INSERT INTO nx_product");
        assertThat(mapper).contains("sandbox=1", "canonical_revision", "nx_commerce_sandbox_catalog",
                "nx_commerce_sandbox_order", "nx_commerce_sandbox_inventory")
                .doesNotContain(" FROM nx_order ", " JOIN nx_order ");
    }

    @Test
    void replayIsPersistedAsTheOriginalResponseRatherThanReprojectedFromCurrentOrderState() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/application/CommerceAcceptanceSandboxService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(mapper).contains("canonical_status canonicalStatus", "result_version resultVersion", "wallet_after walletAfter");
        assertThat(service).contains("replay.canonicalStatus()", "replay.resultVersion()", "replay.walletAfter()",
                "mapper.insertAudit").doesNotContain("auditLogService.recordRequired", "replay.orderNo());\n        if (current");
    }

    @Test
    void orderViewsJoinInventoryWithinTheSameAcceptanceRun() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(mapper).contains("i.order_no=o.order_no AND i.run_id=o.run_id AND i.is_deleted=0");
    }

    @Test
    void firstUseReceiptAvoidsAbsentKeyLocksAndRetriesOnlyTheReceiptClaim() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(mapper).contains("SandboxOrderReceipt findOrderReceipt", "INSERT IGNORE INTO nx_commerce_sandbox_order_receipt");
        assertThat(service).contains("for (int attempt = 0; attempt < 3; attempt++)", "DuplicateKeyException | TransientDataAccessException",
                "findOrderReceipt(runId, userId, key)", "currentSandboxReceipt(runId, userId, key)",
                "lockOrderReceipt(runId, userId, key)");
        assertThat(mapper).contains("A plain read deliberately avoids an absent-key next-key lock");
    }

    @Test
    void callbackRechecksInboxWithACurrentLockingReadAfterTheOrderLock() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/application/CommerceAcceptanceSandboxService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(mapper).contains("Callback lockCurrentCallback", "LIMIT 1 FOR UPDATE");
        assertThat(service).contains("mapper.lockCurrentCallback(runId, normalizedEventId)",
                "plain select can retain the stale no-row snapshot");
    }
}
