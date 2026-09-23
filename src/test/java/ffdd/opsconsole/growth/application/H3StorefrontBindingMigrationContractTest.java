package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class H3StorefrontBindingMigrationContractTest {
    @Test
    void normalStartupInstallsTheExactWeeklyThresholdBindingWithoutOverwritingOperators() throws Exception {
        String name = "20260923_h3_storefront_three_product_binding.sql";
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String sql = Files.readString(Path.of("scripts/migrations", name));

        assertThat(runner).contains(name);
        assertThat(sql).contains("'WEEKLY_STORE_THREE_PRODUCTS','SYSTEM','H3_STOREFRONT_THREE_PRODUCTS_VIEWED'")
                .contains("m.mission_code='weekly_t2_browse_store'")
                .contains("m.mission_type='WEEKLY_T2'")
                .contains("'user_id',1")
                .contains("b.binding_code='WEEKLY_STORE_THREE_PRODUCTS'")
                .contains("b.quest_code=m.mission_code AND b.is_deleted=0");
    }
}
