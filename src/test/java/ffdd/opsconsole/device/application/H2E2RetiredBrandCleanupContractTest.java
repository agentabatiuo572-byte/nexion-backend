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
 * ② 种子写入新品牌,已登记的历史迁移保持原字节;
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
        assertThat(sql).contains("REGEXP '(^|[^[:alnum:]])[Nn]exion'");
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
    void seedsWriteTheCurrentBrand() throws IOException {
        for (Path source : List.of(
                Path.of("scripts/seed_e2_task_pricing.sql"),
                Path.of("scripts/seed-e2-tasks.mjs"))) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(text).as(source.toString()).doesNotContain("需 NexionRack").doesNotContain("需 NexionBox Pro");
            assertThat(text).as(source.toString()).contains("需 NexGridRack");
        }
    }

    /**
     * 🔴 本门钉住一条会**把后端启动打挂**的不变量:
     * 「后置断言拒绝的集合」必须被「UPDATE 能改动的集合」覆盖。
     *
     * 谓词用 REGEXP,而 MySQL 8 的 REGEXP 跟随列排序规则 —— 默认 *_ci 排序规则下
     * `[Nn]exion` 连 `NEXION` / `nExIoN` 都命中;`REPLACE()` 却是大小写敏感的,
     * 只认字面量 `Nexion`。两者一旦不同源,库里只要有一行 `NEXION`,
     * 就会「谓词命中 → 改不动 → 断言失败 → SIGNAL 45000 → 后端启动中止」。
     *
     * 实测教训:首版正是 `REPLACE(name,'Nexion','NexGrid')`,红测无法覆盖(需要真实
     * 大小写数据),所以这里改用**构造性判据**:凡在 WHERE/断言里用大小写不敏感谓词
     * 匹配旧品牌的地方,替换必须走 `REGEXP_REPLACE(...,'i')`。
     */
    @Test
    void brandReplacementIsCaseInsensitiveSoThePostconditionIsSatisfiable() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .as("替换必须大小写不敏感,否则 NEXION 这类行会让后置断言失败并中止启动")
                .contains("REGEXP_REPLACE(name, '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i')");
        assertThat(sql)
                .as("大小写敏感的 REPLACE() 不能再出现在品牌替换里(谓词是 ci 的,两者不同源)")
                .doesNotContain("REPLACE(name, 'Nexion', 'NexGrid')")
                .as("替换也必须带前边界,否则 annexion 会被改成 anNexGrid")
                .doesNotContain("REGEXP_REPLACE(name, 'Nexion', 'NexGrid', 1, 0, 'i')");
    }
}
