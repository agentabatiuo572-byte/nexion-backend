package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TrustHomepageContentMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "scripts/migrations/20260820_i4_homepage_trust_content.sql");

    @Test
    void startupInstallsHomepageTrustFieldsIntoCanonicalI4BusinessTables() throws Exception {
        String runner = Files.readString(
                Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        assertThat(runner).contains("20260820_i4_homepage_trust_content.sql");

        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        assertThat(sql)
                .contains("START TRANSACTION")
                .contains("nx_trust_section_version")
                .contains("nx_trust_section_field")
                .contains("'complianceBadges'")
                .contains("'auditsReserves'")
                .contains("badge7Label")
                .contains("homepageProof.zh")
                .contains("homepageProof.vi")
                .contains("homepageProof.en")
                .contains("section_row.version_label = 'v1'")
                .contains("section_row.last_operator = 'migration'")
                .contains("version_row.last_operator = 'migration'")
                .contains("existing.version_label = 'v2'")
                .contains("WHERE @upgrade_compliance = 1")
                .contains("WHERE @upgrade_audits = 1")
                .contains("last_operator = @homepage_trust_operator")
                .contains("last_operator = 'migration'")
                .contains("COMMIT");
        assertThat(count(sql, "WHERE @upgrade_compliance = 1")).isGreaterThanOrEqualTo(4);
        assertThat(count(sql, "WHERE @upgrade_audits = 1")).isGreaterThanOrEqualTo(4);
    }

    private static int count(String source, String needle) {
        int occurrences = 0;
        for (int index = source.indexOf(needle); index >= 0; index = source.indexOf(needle, index + needle.length())) {
            occurrences++;
        }
        return occurrences;
    }
}
