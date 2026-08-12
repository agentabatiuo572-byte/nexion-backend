package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FundsSandboxIsolationContractTest {
    @Test
    void sandboxFundsCannotReachProductionWalletWithdrawalOrProviderPayoutTables() throws Exception {
        String active = (Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/application/FundsSandboxService.java"))
                + Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/mapper/FundsSandboxMapper.java")))
                .toLowerCase();

        assertThat(active)
                .contains("nx_funds_sandbox_wallet")
                .contains("nx_funds_sandbox_order")
                .contains("nx_funds_sandbox_ledger")
                .contains("nx_funds_sandbox_callback_inbox")
                .doesNotContain("nx_user_wallet")
                .doesNotContain("nx_wallet_ledger")
                .doesNotContain("nx_withdrawal_order")
                .doesNotContain("createpayout(");
    }

    @Test
    void migrationMakesMockSourceAndSandboxEnvironmentDatabaseInvariants() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260811_funds_persistent_sandbox.sql"));

        assertThat(migration)
                .contains("UNIQUE KEY uk_funds_sandbox_order_run_idem (run_id,user_id,idempotency_key)")
                .contains("UNIQUE KEY uk_funds_sandbox_callback_run_event (run_id,event_id)")
                .contains("source = 'mock' AND source_environment = 'SANDBOX'")
                .doesNotContain("nx_user_wallet")
                .doesNotContain("nx_withdrawal_order");
    }
}
