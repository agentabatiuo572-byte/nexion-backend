package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppStoreProductNotificationSandboxIsolationContractTest {
    @Test
    void mapperScopesProductionAndSandboxByEnvironmentAndRun() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/mapper/AppStoreProductNotificationMapper.java"));
        assertThat(mapper).contains("source_environment='PRODUCTION' AND run_id=''",
                "source_environment=#{sourceEnvironment} AND run_id=#{runId}",
                "activeSandboxUser", "COALESCE(sandbox,0)=0");
    }

    @Test
    void migrationSchemaAndStartupUseTheSameFence() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260816_store_product_notification.sql"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(migration).contains("source_environment", "run_id", "CONCAT(user_id, ':', source_environment, ':', run_id");
        assertThat(schema).contains("source_environment", "run_id", "chk_product_notification_environment");
        assertThat(startup).contains("20260816_store_product_notification.sql");
    }
}
