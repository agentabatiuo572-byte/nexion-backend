package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NovaConversationHistoryMigrationContractTest {
    @Test
    void startupMigrationCreatesTheCanonicalDurableNovaTurnHistory() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260824_nova_conversation_history.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS nx_nova_conversation_turn")
                .contains("UNIQUE KEY uk_nova_turn_user_turn (user_id, turn_id)")
                .contains("KEY idx_nova_turn_user_conversation (user_id, conversation_id, id)")
                .doesNotContain("TEMPORARY");
        assertThat(runner).contains("20260824_nova_conversation_history.sql");
    }
}
