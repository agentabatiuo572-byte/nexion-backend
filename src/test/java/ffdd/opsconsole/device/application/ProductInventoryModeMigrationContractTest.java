package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductInventoryModeMigrationContractTest {
    @Test
    void migrationIsIdempotentAndOnlyMarksTheStableCloudShareProductUnlimited() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts", "migrations", "20260825_product_inventory_mode.sql"));
        String startup = Files.readString(Path.of("scripts", "apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("information_schema.COLUMNS", "inventory_mode VARCHAR(16) NOT NULL DEFAULT ''FINITE''")
                .contains("product_no='cloud-share'", "UPPER(product_type)='SHARE'", "inventory_mode='UNLIMITED'")
                .contains("chk_product_inventory_mode", "chk_product_unlimited_share", "'FINITE'',''UNLIMITED'")
                .contains("inventory_mode = ''FINITE'' OR UPPER(product_type) = ''SHARE''")
                .contains("nx_commerce_sandbox_catalog", "chk_commerce_sandbox_catalog_unlimited_share")
                .doesNotContain("2147483647");
        assertThat(startup).contains("20260825_product_inventory_mode.sql");
    }
}
