package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class I6I7A4EventClosureContractTest {

    @Test
    void learningCompletionKeepsH3DeliveryTypeAndUsesGovernedA4LearnSchema() throws Exception {
        String learning = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/content/application/AppLearningService.java"),
                StandardCharsets.UTF_8);
        String outbox = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/outbox/EventOutboxService.java"),
                StandardCharsets.UTF_8);
        Path migrationPath = Path.of("scripts/migrations/20260722_i6_i7_a4_event_closure.sql");

        assertThat(learning)
                .contains("LEARNING_COURSE_COMPLETED")
                .contains("nex_reward");
        assertThat(outbox)
                .contains("LEARNING_COURSE_COMPLETED")
                .contains("learn.course_completed");
        assertThat(migrationPath).exists();

        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
        assertThat(migration)
                .contains("'learn.course_completed'")
                .contains("INSERT INTO nx_event_schema_registry")
                .contains("INSERT INTO nx_event_schema_property")
                .contains("INSERT INTO nx_event_domain_extension")
                .contains("'course_id'", "'course_version'", "'nex_reward'")
                .contains("is_server_authoritative");
    }
}
