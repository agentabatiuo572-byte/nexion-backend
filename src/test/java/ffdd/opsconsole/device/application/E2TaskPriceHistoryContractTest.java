package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E2TaskPriceHistoryContractTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    @Test
    void priceHistoryUsesARealRerunnableBusinessMigration() throws Exception {
        String migration = Files.readString(ROOT.resolve(
                "scripts/migrations/20260820_e2_task_price_history.sql"));
        String runner = Files.readString(ROOT.resolve("scripts/apply_startup_schema_migrations.ps1"));
        String mapper = Files.readString(ROOT.resolve(
                "src/main/java/ffdd/opsconsole/device/mapper/DeviceCatalogMapper.java"));
        String historyService = Files.readString(ROOT.resolve(
                "src/main/java/ffdd/opsconsole/device/application/E2TaskPriceHistoryService.java"));
        String deviceService = Files.readString(ROOT.resolve(
                "src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS nx_admin_device_task_price_history")
                .contains("uk_admin_task_price_history_sample")
                .contains("idx_admin_task_price_history_observed_at")
                .contains("idx_admin_task_price_history_task_time")
                .doesNotContainIgnoringCase("TEMPORARY TABLE");
        assertThat(runner).contains("20260820_e2_task_price_history.sql");
        assertThat(mapper).doesNotContain("ON DUPLICATE KEY UPDATE id = id")
                .contains("ON DUPLICATE KEY UPDATE sample_key = VALUES(sample_key)");
        assertThat(historyService).contains("E2_TASK_PRICE_HISTORY_SCHEMA_INVALID")
                .doesNotContain("createTaskPriceHistoryTable")
                .doesNotContain("countTaskPriceHistory");
        assertThat(mapper).contains("MAX(NON_UNIQUE) = 0")
                .contains("GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'task_id,sample_key'")
                .contains("DATETIME_PRECISION = 3")
                .contains("NUMERIC_PRECISION = 18 AND NUMERIC_SCALE = 8");
        assertThat(deviceService).doesNotContain("@Transactional\n    public ApiResult<DeviceTaskView> createTask")
                .doesNotContain("@Transactional\n    public ApiResult<DeviceTaskView> updateTask")
                .doesNotContain("@Transactional\n    public ApiResult<DeviceTaskView> updateTaskPrice");
    }
}
