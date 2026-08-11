package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E18TaskAssignmentStartupMigrationContractTest {
    @Test
    void startupAppliesE18AndFreshAndUpgradedSchemasHaveTheSameEnvironmentBoundary() throws Exception {
        String startup = read("scripts/apply_startup_schema_migrations.ps1");
        String migration = read("scripts/migrations/20260810_e18_task_assignment_runtime.sql");
        String baseline = read("scripts/schema.sql");

        assertThat(startup).contains("20260810_e18_task_assignment_runtime.sql");
        assertThat(migration).contains(
                "information_schema.COLUMNS",
                "COLUMN_NAME = 'completion_nonce'",
                "COLUMN_NAME = 'source_environment'",
                "ALTER TABLE nx_compute_task ADD COLUMN completion_nonce",
                "ALTER TABLE nx_compute_task ADD COLUMN source_environment",
                "PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;",
                "DROP INDEX uk_compute_device_task_lock_device",
                "ADD UNIQUE KEY uk_compute_device_task_lock_device_env (user_device_id, source_environment)",
                "CREATE TABLE IF NOT EXISTS nx_compute_sandbox_reward")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
        assertThat(baseline).contains(
                "CREATE TABLE IF NOT EXISTS nx_compute_task",
                "completion_nonce CHAR(64)",
                "proof_consumed_at DATETIME",
                "source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION'",
                "CREATE TABLE IF NOT EXISTS nx_compute_device_task_lock",
                "UNIQUE KEY uk_compute_device_task_lock_device_env (user_device_id, source_environment)",
                "CREATE TABLE IF NOT EXISTS nx_compute_sandbox_reward");
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
