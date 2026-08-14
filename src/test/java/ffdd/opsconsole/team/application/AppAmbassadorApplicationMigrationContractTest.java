package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppAmbassadorApplicationMigrationContractTest {

    @Test
    void startupMigrationCreatesTheTableBeforeAnyForwardAlter() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260814_team_ambassador_application.sql"));
        int create = sql.indexOf("CREATE TABLE IF NOT EXISTS nx_team_ambassador_application");
        int alter = sql.indexOf("ALTER TABLE nx_team_ambassador_application");

        assertThat(create).isGreaterThanOrEqualTo(0);
        assertThat(alter).isGreaterThan(create);
        assertThat(sql).contains("UNIQUE KEY uk_ambassador_app_idempotency (user_id, source_environment, run_id, idempotency_key)");
    }
}
