package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class H3WeeklyEventGateMigrationContractTest {
    @Test
    void startupBindsVerifiedFactsAndPausesUnverifiableWeeklyDefinitions() throws Exception {
        String name = "20260923_h3_weekly_event_gate.sql";
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String sql = Files.readString(Path.of("scripts/migrations", name));

        assertThat(runner).contains(name);
        assertThat(sql)
                .contains("'weekly_t2_invite_friend','H3_REFERRAL_REGISTERED'")
                .contains("'weekly_t2_nex_swap','H3_EXCHANGE_COMPLETED'")
                .contains("'weekly_t2_ai_jobs_50','H3_COMPUTE_COMPLETED_50'")
                .contains("'weekly_t2_genesis_browse','H3_GENESIS_SECONDARY_MARKET_VIEWED'")
                .contains("b.quest_code=m.mission_code AND b.status=1 AND b.is_deleted=0")
                .contains("'weekly_t1_buy_additional_hw'")
                .contains("'weekly_t2_reinvest'")
                .contains("r.first_applied_at=@h3_weekly_gate_rollout_at");
    }
}
