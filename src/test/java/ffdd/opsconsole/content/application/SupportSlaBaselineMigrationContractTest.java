package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportSlaBaselineMigrationContractTest {
    private static final String[] CATEGORIES = {
            "account", "withdrawal", "deposit", "hardware",
            "earnings", "genesis", "technical", "other"
    };

    @Test
    void migrationAndCleanSchemaContainTheCompleteIdempotentSlaMatrix() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260728_m4_complete_sla_matrix.sql"),
                StandardCharsets.UTF_8);
        String schema = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);

        assertThat(migration).contains("INSERT IGNORE INTO nx_support_sla_rule");
        assertThat(schema).contains("INSERT IGNORE INTO nx_support_sla_rule");
        for (String category : CATEGORIES) {
            assertThat(migration).contains("'" + category + "'");
            assertThat(schema).contains("'" + category + "'");
        }
    }
}
