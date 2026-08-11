package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsH5HomeCatalogMigrationTest {
    @Test
    void startupMigrationMakesTheAuthenticatedH5HomeAnAllowedBehaviorRoute() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260811_l6_h5_runtime_contract_fix.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("INSERT INTO nx_behavior_page_catalog")
                .contains("'/pages/index/index'")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("tracked=1")
                .contains("is_deleted=0");
        assertThat(startup).contains("20260811_l6_h5_runtime_contract_fix.sql");
    }

    @Test
    void startupCarriesTheCompleteActiveUniAppRouteCatalogAfterTheHomeRepair() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260811_l6_h5_active_route_catalog.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("INSERT INTO nx_behavior_page_catalog")
                .contains("'/pages/learn/course'")
                .contains("'/pages/learn/courses'")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("tracked=1")
                .contains("is_deleted=0");
        assertThat(startup).contains("20260811_l6_h5_active_route_catalog.sql");
    }
}
