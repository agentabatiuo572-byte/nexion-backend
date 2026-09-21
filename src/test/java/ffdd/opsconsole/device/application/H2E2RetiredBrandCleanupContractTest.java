package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * zentao #73 / #39 的旧品牌残留:商品名只被早期迁移改过一行,E2 任务门槛文案
 * (「需 NexionBox Pro」「需 NexionRack」)从未改,而后端写入白名单已是新品牌 ——
 * 运营看到旧值却存不回去(TASK_REQUIREMENT_INVALID)。本门钉住:
 * ① 存量迁移覆盖商品与任务两侧、判据与 RetiredBrandGate 同口径;
 * ② 种子/历史迁移写入的是新品牌,否则新库会重建刚被清掉的旧值;
 * ③ 迁移已注册进启动链。
 */
class H2E2RetiredBrandCleanupContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20260921_h2_e2_retired_brand_cleanup.sql");
    private static final Path STARTUP = Path.of("scripts/apply_startup_schema_migrations.ps1");
    /** 与 OpsDeviceService.TASK_REQUIREMENTS 的枚举逐字一致。 */
    private static final List<String> CANONICAL_REQUIREMENTS = List.of("需 NexGridBox Pro", "需 NexGridRack");

    @Test
    void cleanupCoversProductsAndTaskRequirementsWithTheRetiredBrandRegex() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("UPDATE nx_product");
        assertThat(sql).contains("UPDATE nx_admin_device_task");
        // 与 RetiredBrandGate.RETIRED_BRAND_REGEX 同一口径的整词匹配。
        assertThat(sql).contains("REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'");
        CANONICAL_REQUIREMENTS.forEach(value -> assertThat(sql).contains("'" + value + "'"));
    }

    @Test
    void cleanupRaisesInsteadOfSilentlyPassingWhenRowsRemain() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("SIGNAL SQLSTATE ''45000''");
        assertThat(sql).contains("H2_E2_RETIRED_BRAND_CLEANUP_INCOMPLETE");
    }

    @Test
    void migrationIsRegisteredInTheStartupChain() throws IOException {
        String startup = Files.readString(STARTUP, StandardCharsets.UTF_8);

        assertThat(startup).contains("20260921_h2_e2_retired_brand_cleanup.sql");
    }

    @Test
    void seedsAndHistoricalMigrationWriteTheCurrentBrand() throws IOException {
        // 新库由种子/历史迁移建行:它们若还写旧品牌,清理迁移刚修好的值会被重建。
        for (Path source : List.of(
                Path.of("scripts/seed_e2_task_pricing.sql"),
                Path.of("scripts/seed-e2-tasks.mjs"),
                Path.of("scripts/migrations/20260720_e2_task_pricing_closure.sql"))) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(text).as(source.toString()).doesNotContain("需 NexionRack").doesNotContain("需 NexionBox Pro");
            assertThat(text).as(source.toString()).contains("需 NexGridRack");
        }
    }
}
