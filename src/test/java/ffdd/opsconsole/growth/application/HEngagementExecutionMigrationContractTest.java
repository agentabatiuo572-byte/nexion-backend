package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HEngagementExecutionMigrationContractTest {
    @Test
    void migrationContainsQuestFactWheelTicketSpinGuardsAndExactEvents() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260722_h_engagement_execution.sql"));

        assertThat(sql)
                .contains("nx_growth_quest_completion_fact")
                .contains("nx_growth_spin_ticket")
                .contains("nx_growth_wheel_spin")
                .contains("uk_growth_wheel_spin_source")
                .contains("uk_growth_wheel_spin_daily")
                .contains("reward_amount")
                .contains("daily_stock")
                .contains("'DAILY_CHECK_IN'")
                .contains("mission_type='DAILY'")
                .contains("quest.completed")
                .contains("daily.checkin")
                .contains("daily.spin_awarded")
                .contains("event.spin_awarded")
                .contains("VALUES (1,107)");
    }
}
