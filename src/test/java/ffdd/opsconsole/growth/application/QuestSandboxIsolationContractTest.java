package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuestSandboxIsolationContractTest {
    @Test
    void sandboxQuestProgressNeverReachesProductionUserMissionFacts() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/application/AppGrowthWheelSandboxService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/AppGrowthWheelSandboxMapper.java"));
        assertThat(service).contains("questState", "claimQuest", "sourceEnvironment", "runId");
        assertThat(mapper).contains(
                "nx_growth_quest_sandbox", "run_id=#{runId}", "user_id=#{userId}",
                "syncActiveWeeklyQuests", "FROM nx_mission m",
                "m.mission_type IN ('WEEKLY_T1','WEEKLY_T2')", "m.status=1",
                "column_name='claim_idempotency_key'", "countActiveWeeklyCodeCollisions");
        assertThat(mapper).doesNotContain(
                "FROM nx_user_mission", "UPDATE nx_mission", "INSERT INTO nx_mission");
    }

    @Test
    void historicalWeeklyCollisionUsesReservedCodeInsteadOfMutableSandboxLayer() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/AppGrowthWheelSandboxMapper.java"));
        int method = mapper.indexOf("int countActiveWeeklyCodeCollisions");
        int collisionSqlStart = mapper.lastIndexOf("SELECT COUNT(*)", method);
        String collisionSql = mapper.substring(collisionSqlStart, method);

        assertThat(collisionSql).contains(
                "LOWER(q.quest_code) IN",
                "'bind_bank_card'", "'visit_earn'", "'visit_store'",
                "'view_product_roi'", "'setup_profile'", "'invite_friend'");
        assertThat(collisionSql).doesNotContain("q.layer='DAY_ONE'", "q.is_deleted=0");
    }

    @Test
    void sandboxQuestSchemaIsInBaselineAndControlledStartup() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260816_growth_quest_sandbox.sql"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(migration).contains("nx_growth_quest_sandbox", "uk_growth_quest_sandbox_scope", "source_environment");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS nx_growth_quest_sandbox");
        assertThat(startup).contains("20260816_growth_quest_sandbox.sql");
    }
}
