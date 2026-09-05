package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OrderQuotaReservationLineageMigrationContractTest {
    @Test
    void startupInstallsThePerLineQuotaReservationMarkerWithoutGuessingHistory() throws Exception {
        String filename = "20260902_order_quota_reservation_lineage.sql";
        String generationFilename = "20260902_order_quota_gate_generation.sql";
        String sql = Files.readString(Path.of("scripts/migrations", filename));
        String generationSql = Files.readString(Path.of("scripts/migrations", generationFilename));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(startup).contains(filename, generationFilename);
        assertThat(sql).contains("lifetime_quota_reserved", "DEFAULT 0")
                .contains("information_schema.COLUMNS")
                .doesNotContain("UPDATE nx_order_item SET lifetime_quota_reserved=1");
        assertThat(generationSql).contains("purchase_gate_generation", "lifetime_quota_gate_generation")
                .contains("information_schema.COLUMNS");
    }
}
