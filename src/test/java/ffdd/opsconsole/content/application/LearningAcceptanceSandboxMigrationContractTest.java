package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LearningAcceptanceSandboxMigrationContractTest {

    @Test
    void isolatedLearningMigrationDeclaresTheCompleteSandboxProjectionSchema() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260812_learning_acceptance_sandbox.sql"))
                .toLowerCase();

        assertThat(sql)
                .contains("create table if not exists nx_learning_sandbox_progress")
                .contains("create table if not exists nx_learning_sandbox_event")
                .contains("create table if not exists nx_learning_sandbox_reward_ledger")
                .contains("create table if not exists nx_learning_sandbox_idempotency")
                .contains("create table if not exists nx_learning_sandbox_course")
                .contains("create table if not exists nx_learning_sandbox_admin_idempotency")
                .contains("user_id bigint not null")
                .contains("course_id varchar(96) not null")
                .contains("course_version varchar(64) not null")
                .contains("progress_pct int not null")
                .contains("attempts int not null")
                .contains("last_score int not null")
                .contains("reward_no varchar(160) not null")
                .contains("idempotency_key varchar(128) not null")
                .contains("request_hash char(64) not null")
                .contains("result_json json null")
                .contains("run_id varchar(64) not null")
                .contains("amount_nex decimal(24,6) not null")
                .contains("source varchar(16) not null default 'mock'")
                .contains("source_environment varchar(16) not null default 'sandbox'")
                .contains("uk_learning_sandbox_progress_user_course_version")
                .contains("uk_learning_sandbox_event_once")
                .contains("uk_learning_sandbox_reward_user_course_version")
                .contains("uk_learning_sandbox_idempotency_attempt")
                .contains("uk_learning_sandbox_course_run_version")
                .contains("published_course_id varchar(96) generated always")
                .contains("uk_learning_sandbox_course_one_published")
                .contains("uk_learning_sandbox_admin_idempotency")
                .contains("information_schema.columns")
                .contains("information_schema.tables")
                .contains("table_collation")
                .contains("convert to character set utf8mb4 collate utf8mb4_0900_ai_ci")
                .contains("default charset=utf8mb4 collate=utf8mb4_0900_ai_ci")
                .doesNotContain("default charset=utf8mb4 collate=utf8mb4_unicode_ci")
                .contains("prepare learning_stmt from @learning_sql")
                .doesNotContain("add column if not exists")
                .doesNotContain("drop index if exists")
                .contains("group_concat(column_name order by seq_in_index)")
                .contains("'run_id,user_id,course_id,course_version'")
                .contains("'run_id,user_id,course_id,course_version,event_type'")
                .contains("'run_id,user_id,course_id,course_version,idempotency_key'")
                .contains("uk_learning_sandbox_progress_run_user_course_version");
    }
}
