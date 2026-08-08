package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Bep20WithdrawalToggleContractTest {

    @Test
    void startupMigrationSeedsCanonicalAndWalletMirrorWithoutOverwritingRuntimeValues() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260809_bep20_withdrawal_toggle.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("'withdrawal.bep20.enabled'")
                .contains("'wallet.withdrawal.bep20.enabled'")
                .contains("withdrawal.erc20.enabled")
                .contains("COALESCE")
                .contains("), 'false');")
                .doesNotContain("), 'true');")
                .contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("config_value=VALUES(config_value)");
        assertThat(runner).contains("20260809_bep20_withdrawal_toggle.sql");
    }
}
