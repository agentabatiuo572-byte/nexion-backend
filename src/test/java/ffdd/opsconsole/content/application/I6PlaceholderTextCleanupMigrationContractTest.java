package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * zentao #67:完整性扫描加了内容判据,但「已发布正文其实是占位」的存量行没人清理,
 * 于是运营每次打开 I6 都看到同一批未处理项。本门钉住清理迁移的存在性、与扫描侧
 * 同口径的判据、以及它已注册进启动链 —— 三者缺一,缺陷就会复发。
 */
class I6PlaceholderTextCleanupMigrationContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20260921_i6_published_placeholder_text_cleanup.sql");
    private static final Path STARTUP = Path.of("scripts/apply_startup_schema_migrations.ps1");

    @Test
    void cleanupMatchesTheScannerCriteriaAndOnlyTouchesPublishedRows() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // 与 MybatisI18nLearningRepository.isPlaceholderText 的两条判据同形:
        // 整串同字符重复、整串只由测试词组成。
        assertThat(sql).contains("REGEXP '^(.)\\\\1{2,}$'");
        assertThat(sql).contains("REGEXP '^(test|todo|tbd|placeholder|dummy|样例|测试|占位)+$'");
        // 只退回草稿,不发明替代文案(status=1 是已发布)。
        assertThat(sql).contains("SET status = 0");
        assertThat(sql).contains("WHERE status = 1");
    }

    @Test
    void cleanupRaisesInsteadOfSilentlyPassingWhenRowsRemain() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("SIGNAL SQLSTATE ''45000''");
        assertThat(sql).contains("I6_PLACEHOLDER_TEXT_CLEANUP_INCOMPLETE");
    }

    @Test
    void migrationIsRegisteredInTheStartupChain() throws IOException {
        String startup = Files.readString(STARTUP, StandardCharsets.UTF_8);

        assertThat(startup).contains("20260921_i6_published_placeholder_text_cleanup.sql");
    }
}
