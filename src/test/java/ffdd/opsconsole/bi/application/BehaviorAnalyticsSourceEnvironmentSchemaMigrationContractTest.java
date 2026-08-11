package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsSourceEnvironmentSchemaMigrationContractTest {
    private static final Path MIGRATION =
            Path.of("scripts/migrations/20260811_l6_source_environment_schema.sql");

    @Test
    void startupMigrationRegistersTheServerOwnedEnvironmentForBothBehaviorEvents() throws Exception {
        String migration = normalized(MIGRATION);
        String startup = normalized(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String producer = normalized(
                Path.of("src/main/java/ffdd/opsconsole/bi/application/BehaviorAnalyticsService.java"));

        assertThat(migration)
                .contains("'app.page_viewed'")
                .contains("'app.element_clicked'")
                .contains("'source_environment','enum',1")
                .contains("current_revision=GREATEST(current_revision,306)")
                .contains("s.current_revision=306")
                .contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("DELETE FROM nx_event_schema_property");
        assertThat(startup).contains("20260811_l6_source_environment_schema.sql");
        assertThat(producer)
                .contains("payload.put(\"source_environment\",sourceEnvironment)")
                .doesNotContain("request.sourceEnvironment()");
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }
}
