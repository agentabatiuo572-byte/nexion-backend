package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class H3ActiveMissionAlignmentMigrationContractTest {
    @Test
    void migrationKeepsHistoryButStopsFactsFromCompletingRetiredTasks() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260902_h3_active_mission_event_alignment.sql"));

        assertThat(sql).contains("quest_code='invite_friend'", "binding_code='REFERRAL_SETTLED'")
                .contains("ORDER_STARTED", "LEARNING_COMPLETED", "DEVICE_ACTIVATED", "COMMISSION_UNLOCKED")
                .contains("SET status=0")
                .doesNotContain("DELETE FROM nx_mission");

        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(startup).contains("20260902_h3_active_mission_event_alignment.sql");
    }

    @Test
    void runtimeBindingReadsRequireTheTargetMissionToStillBeActive() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/QuestCanonicalEventBindingMapper.java"));

        assertThat(mapper).contains("JOIN nx_mission m", "m.status=1", "m.is_deleted=0");
    }
}
