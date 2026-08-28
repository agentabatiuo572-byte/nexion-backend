package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TrialConversionOrderBackfillMigrationContractTest {
    @Test
    void migrationBackfillsOnlyWalletChargedLegacyTrialDevicesWithoutRepeatingMoneyOrStock() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260826_trial_conversion_order_backfill.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("START TRANSACTION", "TRC-LEGACY-", "TRIAL_CHARGE", "payment_status",
                        "'PAID'", "nx_order_item", "source_order_no", "COMMIT")
                .doesNotContain("UPDATE nx_wallet", "decrementProductStock", "stock=", "stock =");
        assertThat(startup).contains("20260826_trial_conversion_order_backfill.sql");
    }
}
