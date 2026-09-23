package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class I6InvalidPublishedKeyRetirementContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20260923_i6_invalid_published_key_retirement.sql");

    @Test
    void invalidPublishedKeyRemovesEveryLocaleFromThePublicBundle() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        String repository = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/infrastructure/MybatisI18nLearningRepository.java"), StandardCharsets.UTF_8);
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);

        // A previously retired ZH row can only be discovered through its still
        // PUBLISHED version; EN/VI may remain status=1 after the older migration.
        assertThat(sql).contains("FROM nx_i18n_message_version")
                .contains("REGEXP_REPLACE(zh_value")
                .contains("REGEXP_REPLACE(en_value")
                .contains("REGEXP_REPLACE(vi_value")
                .contains("CONCAT_WS(' ', zh_value, en_value, vi_value)");
        assertThat(sql).contains("JOIN i6_invalid_published_keys AS bad ON bad.message_key = m.message_key")
                .contains("SET m.status = 0")
                .doesNotContain("m.locale =")
                .contains("SET v.status = 'ARCHIVED'")
                .contains("I6_INVALID_KEY_STILL_PUBLIC");
        assertThat(repository).contains(".eq(I18nMessageEntity::getLocale, locale)")
                .contains(".eq(I18nMessageEntity::getStatus, 1)");
        assertThat(startup).contains("20260923_i6_invalid_published_key_retirement.sql");
    }
}
