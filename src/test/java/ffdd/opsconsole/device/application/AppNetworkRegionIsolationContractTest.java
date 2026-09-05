package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.device.mapper.AppNetworkRegionMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppNetworkRegionIsolationContractTest {
    @Test
    void everyGlobalProjectionIsProductionOnlyUntilRunScopedDeviceProjectionExists() throws Exception {
        Select select = AppNetworkRegionMapper.class.getMethod("regions", Long.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");
        assertThat(sql).contains("owner.sandbox = 0")
                .contains("viewer.sandbox = 0")
                .contains("viewer.id = #{userId}")
                .contains("COALESCE(t.source_environment, 'PRODUCTION') = 'PRODUCTION'")
                .doesNotContain("viewer.sandbox = 1")
                .doesNotContain("= 'SANDBOX'");
    }

    @Test
    void acceleratedTaskUnionRetainsTheProductionIsolationAndMutuallyExclusiveWindows() throws Exception {
        Select select = AppNetworkRegionMapper.class.getMethod("regions", Long.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        int activeBranch = sql.indexOf("WHERE status IN ('ASSIGNED','RUNNING','PROCESSING')");
        int union = sql.indexOf("UNION ALL");
        int completedBranch = sql.indexOf("WHERE status = 'COMPLETED'");
        assertThat(activeBranch).isGreaterThanOrEqualTo(0);
        assertThat(union).isGreaterThan(activeBranch);
        assertThat(completedBranch).isGreaterThan(union);
        assertThat(sql).contains("AND completed_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)")
                .contains("WHERE t.is_deleted = 0 AND viewer.sandbox = 0 AND owner.sandbox = 0")
                .contains("COALESCE(t.source_environment, 'PRODUCTION') = 'PRODUCTION'")
                .contains("JOIN nx_user owner ON owner.id = d.user_id AND owner.is_deleted = 0")
                .contains("JOIN nx_user viewer ON viewer.id = #{userId} AND viewer.is_deleted = 0");
    }

    @Test
    void globeReadIndexMigrationIsRegisteredIdempotentAndFailsClosedForSameNameWrongShape() throws Exception {
        String migration = Files.readString(
                Path.of("scripts", "migrations", "20260831_globe_task_read_index.sql"), StandardCharsets.UTF_8);
        String startup = Files.readString(Path.of("scripts", "apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);

        assertThat(startup).contains("20260831_globe_task_read_index.sql");
        assertThat(migration).contains("GET_LOCK(@globe_lock_name, 10)")
                .contains("IF(@globe_index_exists = 0,")
                .contains("ADD INDEX idx_compute_task_globe_window (status, completed_at, user_device_id, is_deleted, source_environment)")
                .contains("COUNT(*) = 5")
                .contains("index_type = 'BTREE'")
                .contains("is_visible = 'YES'")
                .contains("FAIL GLOBE_INDEX_SHAPE_INVALID")
                .contains("SELECT RELEASE_LOCK(@globe_lock_name)");
    }
}
