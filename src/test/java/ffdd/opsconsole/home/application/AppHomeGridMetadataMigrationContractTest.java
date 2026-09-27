package ffdd.opsconsole.home.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppHomeGridMetadataMigrationContractTest {
    @Test
    void currentBrandReplacesOnlyTheOriginalHomeGridSeed() throws Exception {
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String baseline = Files.readString(Path.of("scripts/schema.sql"));
        String repair = Files.readString(Path.of("scripts/migrations/20260928_home_mobile_network_brand.sql"));

        assertThat(startup).contains("20260928_home_mobile_network_brand.sql");
        assertThat(baseline).contains("'UVEL Mobile Network'");
        assertThat(repair).contains("dc_location = 'User device'", "is_deleted = 0",
                "updated_by = 'system:home-grid-metadata'",
                "BINARY display_name = BINARY 'NexGrid Mobile Network'", "display_name = 'UVEL Mobile Network'")
                .doesNotContain("nx_compute_task", "nx_product");
    }

    @Test
    void startupBackfillsGridDisplayMetadataInTheE5DatacenterAuthority() throws Exception {
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String migration = Files.readString(
                Path.of("scripts/migrations/20260820_home_grid_datacenter_metadata.sql"));

        assertThat(startup).contains("20260820_home_grid_datacenter_metadata.sql");
        assertThat(migration).contains(
                "INSERT INTO nx_compute_datacenter",
                "'User device'",
                "location",
                "display_name",
                "region_label IS NULL",
                "location IS NULL",
                "display_name IS NULL",
                "is_deleted = 0 AND",
                "ON DUPLICATE KEY UPDATE")
                .doesNotContain("Pocket Studios", "Helix Labs", "Echo Earbuds")
                .doesNotContain("UPDATE nx_user_device")
                .doesNotContain("is_deleted = 0,\n");
    }
}
