package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeamHardwareQuotaCatalogMigrationContractTest {
    @Test
    void legacyQuotaBootstrapIsAlignedToTheCanonicalStoreCatalogWithoutOverwritingOperatorRows() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260823_team_hardware_quota_product_alignment.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration).contains(
                "quota_code='HW-PRO' AND product_no='NEX-NODE-PRO'",
                "quota_code='HW-RACK-STD' AND product_no='NEX-RACK-STD'",
                "product_no='stellarbox-pro'",
                "product_no='stellarrack-p1'",
                "product_no IN ('NEX-NODE-LITE','NEX-RACK-PRO','NEX-CLUSTER-MINI')");
        assertThat(migration).doesNotContain("ON DUPLICATE KEY UPDATE");
        assertThat(startup).contains("20260823_team_hardware_quota_product_alignment.sql");
    }
}
