package ffdd.opsconsole.content.terms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LegalTermsFormalPublishMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "scripts/migrations/20260902_legal_terms_formal_publish.sql");

    @Test
    void startupPublishesFormalVietnameseChineseAndEnglishTermsWithoutRewritingOperatorContent()
            throws Exception {
        assertThat(MIGRATION).exists();
        String migration = Files.readString(MIGRATION);
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(runner).contains(MIGRATION.getFileName().toString());
        assertThat(migration)
                .contains("START TRANSACTION", "COMMIT", "INSERT IGNORE INTO nx_legal_terms_version")
                .contains("'vi', 'GLOBAL', 'v6'", "'zh', 'GLOBAL', 'v6'", "'en', 'GLOBAL', 'v6'")
                .contains("'PUBLISHED'", "JSON_ARRAY(", "status='SUPERSEDED'")
                .doesNotContain("ON DUPLICATE KEY UPDATE", "for acceptance testing only",
                        "MSB registration",
                        "segregated reserve", "smart-contract-based", "binding arbitration");

        assertThat(count(migration, "'sortOrder'"))
                .as("each of the three locales must publish ten complete sections")
                .isEqualTo(30);
        assertThat(migration.indexOf("INSERT IGNORE INTO nx_legal_terms_version"))
                .isLessThan(migration.indexOf("status='SUPERSEDED'"));
        assertThat(migration)
                .contains("EXISTS (", "version_label='v6'", "status='PUBLISHED'")
                .contains("locale='en'", "jurisdiction='GLOBAL'", "version_label='v4'")
                .contains("title='Nexion Acceptance Terms seven-closures-20260817 post-fix-v4'")
                .contains("summary='QA acceptance fixture'")
                .contains("last_operator='migration:formal-terms-v5'")
                .contains("COALESCE(last_operator,'') NOT IN")
                .contains("safe_terms.locale=legacy.locale")
                .contains("safe_terms.jurisdiction=legacy.jurisdiction");
        assertThat(runner)
                .contains("requiredLegalTermsLocaleCount")
                .contains("Legal terms startup postcondition failed");
    }

    private static int count(String source, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
