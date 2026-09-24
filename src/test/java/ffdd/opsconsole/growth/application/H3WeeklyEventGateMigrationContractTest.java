package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class H3WeeklyEventGateMigrationContractTest {
    @Test
    void exchangeFollowupPausesOnlyClosedDefinitionWithoutChangingUserHistory() throws Exception {
        String name = "20260925_h3_exchange_mission_gate.sql";
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String sql = Files.readString(Path.of("scripts/migrations", name));

        assertThat(runner).contains(name);
        assertThat(sql).contains("weekly_t2_nex_swap", "killswitch.exchange",
                "emergency.killswitch.exchange", "nx_growth_mission_gate_pause_receipt",
                "m.status=1", "SET m.status=0", "exchange.swapped",
                "H3_EXCHANGE_COMPLETED", "h3-weekly-exchange-referral-evaluator",
                "h3-quest-completion", "d.status NOT IN ('SUCCESS','SKIPPED')",
                "lock_key='G2_EXCHANGE_EXECUTION' FOR UPDATE");
        assertThat(sql).doesNotContain("UPDATE nx_user_mission", "DELETE FROM nx_user_mission",
                "UPDATE nx_growth_quest_completion_fact");
    }

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
        assertThat(sql)
                .contains("nx_growth_mission_gate_pause_receipt", "previous_status", "m.status=1")
                .contains("SET @h3_weekly_gate_rollout_at := NOW(3);")
                .contains("SET @h3_weekly_pause_at := NOW();")
                .contains("m.updated_at=@h3_weekly_pause_at")
                .contains("h3_weekly_quarantine_ids", "nx_growth_quest_binding_quarantine_receipt")
                .contains("JOIN h3_weekly_quarantine_ids q ON q.binding_id=b.id")
                .contains("CONCAT('H3WK_',SUBSTRING(SHA2(e.quest_code,256),1,32))")
                .doesNotContain("initial_status");
        String recovery = Files.readString(Path.of("scripts/manual_recovery",
                "20260923_h3_weekly_event_gate_recovery_audit.sql"));
        assertThat(recovery).contains("m.updated_at=r.paused_at", "r.id=@h3_restore_receipt_id");
        assertThat(Files.exists(Path.of("scripts/migrations",
                "20260923_h3_weekly_event_gate_recovery_audit.sql"))).isFalse();
    }

    @Test
    void historicalWrongMissionCannotConsumeDerivedWeeklySourceEvent() throws Exception {
        String sql = String.join(" ", QuestCanonicalEventBindingMapper.class
                .getMethod("listActiveBindings", String.class).getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ");
        assertThat(sql).contains("b.event_type NOT IN ('H3_STOREFRONT_THREE_PRODUCTS_VIEWED'",
                "b.quest_code=CASE b.event_type", "b.producer='SYSTEM'", "b.user_id_field='user_id'");
    }
}
