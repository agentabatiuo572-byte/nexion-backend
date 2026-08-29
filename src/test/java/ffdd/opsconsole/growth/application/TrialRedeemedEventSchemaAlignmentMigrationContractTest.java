package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TrialRedeemedEventSchemaAlignmentMigrationContractTest {
    @Test
    void registersEveryCanonicalTrialRedeemedOrderFieldAndRunsAtStartup() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260829_trial_redeemed_event_schema_alignment.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration).contains(
                "'trial.redeemed'",
                "'order_no'",
                "'product_no'",
                "'discount_usdt'",
                "'payment_status'",
                "'order_status'",
                "current_revision=311",
                "current_revision=GREATEST(current_revision,311)");
        assertThat(runner).contains("20260829_trial_redeemed_event_schema_alignment.sql");
    }
}
