package ffdd.opsconsole.bi.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsSandboxMigrationContractTest {
    @Test
    void mysqlEightForwardMigrationScopesAllSandboxIdentityQueriesToRunAndAvoidsUnsupportedIfNotExists() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260812_l6_acceptance_sandbox_fact.sql"), StandardCharsets.UTF_8);
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/bi/mapper/BehaviorAnalyticsSandboxMapper.java"), StandardCharsets.UTF_8);
        String baseline = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);
        assertThat(migration).doesNotContain("ADD COLUMN IF NOT EXISTS")
                .contains("information_schema.COLUMNS", "PREPARE l6_stmt", "DROP INDEX uk_behavior_sandbox_client_event_id",
                        "uk_behavior_sandbox_run_client_event_id", "uk_behavior_sandbox_run_dedupe_key", "observation_token");
        assertThat(mapper).contains("run_id=#{runId} AND client_event_id", "run_id=#{runId} AND dedupe_key",
                "run_id=#{runId} AND session_hash", "observation_token=#{observationToken}");
        assertThat(mapper).contains("FROM nx_behavior_event_fact WHERE actor_hash=#{actorHash} AND session_hash=#{sessionHash}")
                .doesNotContain("nx_behavior_event_fact WHERE source_environment='PRODUCTION'");
        assertThat(baseline).contains("observation_token CHAR(64) NOT NULL", "uk_behavior_sandbox_run_client_event_id",
                "uk_behavior_sandbox_run_dedupe_key", "idx_behavior_sandbox_observation_token");
    }
}
