package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class H2TrialProductCatalogMigrationContractTest {
    @Test
    void migrationReplacesOnlyTheLegacyAliasAndSynchronizesTheE1NameAndPrice() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260826_h2_trial_product_catalog.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("current_value = 'device-trial-standard'", "current_value = 'stellarbox-s1'",
                        "JOIN nx_product", "policy_key = 'trialPriceUSD'", "name = 'NexGridBox S1'",
                        "START TRANSACTION", "policy.current_value <>", "COMMIT")
                .doesNotContain("stock =", "stock=");
        assertThat(startup).contains("20260826_h2_trial_product_catalog.sql");
    }
}
