package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NotificationPreferenceMigrationContractTest {
    @Test
    void preferenceMigrationIsPresentAndMatchesTheCanonicalPreferenceContract() throws Exception {
        Path migrationPath = Path.of("scripts/migrations/20260817_notification_preferences.sql");
        Path runnerPath = Path.of("scripts/apply_startup_schema_migrations.ps1");
        assertThat(Files.exists(migrationPath)).isTrue();
        assertThat(Files.exists(runnerPath)).isTrue();

        String migration = Files.readString(migrationPath);
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS nx_user_preference");
        assertThat(migration).contains(
                "notify_commission", "notify_team", "notify_staking",
                "notify_market", "notify_genesis", "notify_system");

        // The runner owns ordering/registration; root integration registers this path centrally.
        assertThat(Files.readString(runnerPath))
                .contains("$migrations = @(", "foreach ($migration in $migrations)",
                        "20260817_notification_preferences.sql");
    }
}
