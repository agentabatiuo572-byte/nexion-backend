package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContentExperimentRuntimeSchemaContractTest {
    @Test
    void schemaAndMigrationProvideStickyAssignmentAndIdempotentConversionStorage() throws IOException {
        assertRuntimeTables("scripts/schema.sql");
        assertRuntimeTables("scripts/migrations/20260712_i1_copy_schema.sql");
    }

    private void assertRuntimeTables(String relativePath) throws IOException {
        String sql = Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "audience_snapshot_json JSON NULL",
                "CREATE TABLE IF NOT EXISTS nx_content_experiment_assignment",
                "UNIQUE KEY uk_content_experiment_assignment (experiment_id, user_id)",
                "CREATE TABLE IF NOT EXISTS nx_content_experiment_conversion",
                "UNIQUE KEY uk_content_experiment_conversion (experiment_id, user_id)",
                "UPDATE nx_content_experiment experiment",
                "COUNT(DISTINCT CAST(version.audience_json AS CHAR)) = 1",
                "experiment.audience_snapshot_json IS NULL",
                "experiment.state IN ('SCHEDULED', 'RUNNING')");
        if (relativePath.contains("migrations")) {
            assertThat(sql).contains("ADD COLUMN copy_version VARCHAR(32) NULL AFTER variant_name");
        }
    }
}
