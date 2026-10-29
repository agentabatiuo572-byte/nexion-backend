package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Trust-centre documents keep one uploaded file per slot; a slot without a row serves its shipped default. */
class TrustDocumentsMigrationContractTest {
    private static final String MIGRATION = "20261029_trust_documents.sql";

    @Test
    void migrationRunsInTheStartupChainAfterTheRetiredEvents() throws Exception {
        String installer = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\"scripts\\\\migrations\\\\([^\"]+\\.sql)\"").matcher(installer);
        List<String> migrations = new ArrayList<>();
        while (matcher.find()) migrations.add(matcher.group(1));
        assertThat(migrations).containsOnlyOnce(MIGRATION);
        assertThat(migrations.indexOf(MIGRATION))
                .isGreaterThan(migrations.indexOf("20261029_h4_retire_legacy_events.sql"));
        assertThat(migrations).containsOnlyOnce("20261029_l6_trust_document_routes.sql");
    }

    @Test
    void appTrustPagesAreTrackedInTheRouteCatalog() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", "20261029_l6_trust_document_routes.sql"), StandardCharsets.UTF_8);
        assertThat(sql).contains("('/pages/trust/whitepaper',", "('/pages/trust/document',", "ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0;");
    }

    @Test
    void migrationCreatesTheSlotTableIdempotently() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION), StandardCharsets.UTF_8);
        assertThat(sql).contains(
                "CREATE TABLE IF NOT EXISTS nx_trust_document",
                "slot VARCHAR(32) NOT NULL PRIMARY KEY",
                "object_key VARCHAR(256) NOT NULL",
                "sha256 CHAR(64) NOT NULL",
                "page_count INT NOT NULL");
    }
}
