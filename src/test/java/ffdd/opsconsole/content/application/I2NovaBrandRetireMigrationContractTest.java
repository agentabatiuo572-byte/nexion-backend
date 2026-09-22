package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * zentao #147 的旧品牌残留:三条已发布 Nova 模板(social / welcome / wrapped)的
 * 三语正文仍写「Nexion」,而 Nova 会把已发布模板正文原样推送给用户。
 *
 * 本门钉住三件事:
 * ① 迁移覆盖**三语六列**(漏一列就等于把旧品牌留给那一种语言的用户);
 * ② 替换是**大小写不敏感**的,且已注册进启动链;
 * ③ 后置断言在仍有残留时抛错而不是静默通过。
 */
class I2NovaBrandRetireMigrationContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20260920_i2_nova_brand_retire.sql");
    private static final Path STARTUP = Path.of("scripts/apply_startup_schema_migrations.ps1");

    @Test
    void cleanupCoversAllThreeLocalesAndBothColumns() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        // 列对齐用的空白不参与判据:先归一化再比对,否则「改了对齐就红」这种假红会
        // 逼着下一个人把断言放宽到失去意义。
        String normalized = sql.replaceAll("[ \\t]+", " ");

        assertThat(normalized).contains("UPDATE nx_nova_template");
        // 三语 × 标题/正文 = 六列,一列都不能少。
        for (String column : new String[]{"title_zh", "body_zh", "title_vi", "body_vi", "title_en", "body_en"}) {
            assertThat(normalized).as(column).contains(column + " = REGEXP_REPLACE(" + column);
        }
    }

    /**
     * 🔴 同 {@code H2E2RetiredBrandCleanupContractTest}:谓词用大小写不敏感的 REGEXP,
     * 替换若用大小写敏感的 {@code REPLACE()},两者不同源 —— 库里一行 {@code NEXION}
     * 会让后置断言失败并**中止后端启动**。替换必须走 {@code REGEXP_REPLACE(...,'i')}。
     */
    @Test
    void brandReplacementIsCaseInsensitiveSoThePostconditionIsSatisfiable() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("REGEXP_REPLACE(title_zh, '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i')");
        assertThat(sql).contains("REGEXP_REPLACE(body_en,  '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i')");
        assertThat(sql).doesNotContain("REPLACE(title_zh, 'Nexion', 'NexGrid')");
        // 前边界必须出现在替换里,否则 annexion → anNexGrid(实测过)。
        assertThat(sql).doesNotContain("REGEXP_REPLACE(title_zh, 'Nexion', 'NexGrid', 1, 0, 'i')");
    }

    @Test
    void cleanupRaisesInsteadOfSilentlyPassingWhenRowsRemain() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("SIGNAL SQLSTATE ''45000''");
        assertThat(sql).contains("NOVA_TEMPLATE_BRAND_RETIRE_INCOMPLETE");
    }

    @Test
    void migrationIsRegisteredInTheStartupChain() throws IOException {
        String startup = Files.readString(STARTUP, StandardCharsets.UTF_8);

        assertThat(startup).contains("20260920_i2_nova_brand_retire.sql");
    }
}
