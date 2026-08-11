package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F5CommissionExportEventSchemaMigrationContractTest {
    private static final Path MIGRATION =
            Path.of("scripts/migrations/20260811_f5_commission_export_event_schema.sql");

    @Test
    void canonicalF5ExportEventIsRegisteredBeforeTheProducerCanCommit() throws Exception {
        String migration = normalized(MIGRATION);
        String startup = normalized(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String producer = normalized(
                Path.of("src/main/java/ffdd/opsconsole/team/application/F5CommissionService.java"));

        assertThat(producer)
                .contains("\"admin.commission_exported\", detail")
                .contains("\"reason\", reason")
                .contains("\"filters\", linked(")
                .contains("\"rowCount\", written")
                .contains("\"byteSize\", content.length")
                .contains("\"sha256\", digest")
                .contains("\"redacted\", true");
        assertThat(migration)
                .contains("'admin.commission_exported'")
                .contains("'F5CommissionService'")
                .contains("SELECT 'reason' property_name,'string' property_type,1 required_field")
                .contains("SELECT 'filters','json',1")
                .contains("SELECT 'row_count','number',1")
                .contains("SELECT 'byte_size','number',1")
                .contains("SELECT 'sha256','string',1")
                .contains("SELECT 'redacted','boolean',1")
                .contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("DELETE FROM nx_event_schema_property");
        assertThat(countOccurrences(startup, "20260811_f5_commission_export_event_schema.sql"))
                .isEqualTo(1);
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private int countOccurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
