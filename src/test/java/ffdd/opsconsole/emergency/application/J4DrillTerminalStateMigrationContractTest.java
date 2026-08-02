package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class J4DrillTerminalStateMigrationContractTest {
    private static final String TARGET_EXECUTION_ID = "SOP-CUSTOM-10-DRILL-211D828E";
    private static final Path MIGRATION =
            Path.of("scripts/migrations/20260801_j4_drill_terminal_state.sql");

    @Test
    void migrationOnlyFinalizesStrictlyProvenValidationOnlyDrillsAndAppendsAuditEvidence() throws Exception {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("execution_mode = 'drill'")
                .contains("execution_id = '" + TARGET_EXECUTION_ID + "'")
                .contains("rollback_status IS NULL")
                .contains("'NOT_REQUIRED'")
                .contains("'VALIDATION_ONLY_NO_PRODUCTION_ACTIONS'")
                .contains("JSON_TABLE(e.domain_action_json, '$[*]'")
                .contains("action.status <> 'VALIDATED'")
                .contains("J4_SOP_PLAYBOOK_DRILL_COMPLETED")
                .contains("$.productionActionsExecuted')) = 'false'")
                .contains("J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED")
                .contains("START TRANSACTION")
                .contains("COMMIT")
                .contains("NOT EXISTS");
    }

    @Test
    void oneShotRepairIsNotSilentlyReplayedAtEveryBackendStartup() throws Exception {
        String startup = Files.readString(
                Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);

        assertThat(countOccurrences(startup, "20260801_j4_drill_terminal_state.sql")).isZero();
    }

    @Test
    void exactTargetHasOneCandidateGuardAndAllowsOnlyAProvenNoOpRerun() throws Exception {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        int update = migration.indexOf("UPDATE nx_emergency_sop_execution e");
        int insert = migration.indexOf("INSERT INTO nx_audit_log");
        int commit = migration.indexOf("COMMIT");

        assertThat(countOccurrences(migration, "'" + TARGET_EXECUTION_ID + "'")).isGreaterThanOrEqualTo(4);
        assertThat(migration.substring(update, insert))
                .contains("candidate.execution_id = '" + TARGET_EXECUTION_ID + "'")
                .contains("e.execution_id = '" + TARGET_EXECUTION_ID + "'");
        assertThat(migration.substring(insert, commit))
                .contains("candidate.execution_id = '" + TARGET_EXECUTION_ID + "'")
                .contains("e.execution_id = '" + TARGET_EXECUTION_ID + "'");
        assertThat(migration)
                .contains("CREATE TEMPORARY TABLE tmp_j4_drill_terminal_state_candidate_guard")
                .contains("CONSTRAINT ck_j4_drill_terminal_state_candidate_count CHECK (candidate_count = 1)")
                .contains("SELECT COUNT(*) FROM tmp_j4_validation_only_drill_repair")
                .contains("CREATE TEMPORARY TABLE tmp_j4_drill_terminal_state_rerun_guard")
                .contains("CONSTRAINT ck_j4_drill_terminal_state_rerun_state CHECK (rerun_state_valid = 1)")
                .contains("e.rollback_status = 'NOT_REQUIRED'")
                .contains("e.rollback_reason = 'VALIDATION_ONLY_NO_PRODUCTION_ACTIONS'")
                .contains("COUNT(*) = 1")
                .contains("J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED");
    }

    @Test
    void refusesPartialRollbackStateAndSoftDeletedMigrationAuditHistory() throws Exception {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        int update = migration.indexOf("UPDATE nx_emergency_sop_execution e");
        int insert = migration.indexOf("INSERT INTO nx_audit_log");

        assertThat(migration)
                .contains("e.rollback_reason IS NULL OR e.rollback_reason = ''")
                .contains("e.rollback_at IS NULL")
                .contains("e.rollback_action_json IS NULL")
                .contains("JSON_LENGTH(e.rollback_action_json) = 0")
                .contains("FROM nx_audit_log all_history_pending_audit")
                .contains("FROM nx_audit_log all_history_completed_audit")
                .contains("FROM nx_audit_log valid_completed_audit")
                .doesNotContain("all_history_pending_audit.is_deleted")
                .doesNotContain("all_history_completed_audit.is_deleted");
        assertThat(migration.substring(update, insert))
                .contains("e.rollback_reason IS NULL OR e.rollback_reason = ''")
                .contains("e.rollback_at IS NULL")
                .contains("e.rollback_action_json IS NULL")
                .contains("FROM nx_audit_log all_history_pending_audit");
        assertThat(migration.substring(insert))
                .contains("e.rollback_at IS NULL")
                .contains("e.rollback_action_json IS NULL")
                .contains("FROM nx_audit_log all_history_pending_audit");
    }

    @Test
    void concurrentRerunsSerializeBeforeCandidateDiscoveryAndVerifyLockRelease() throws Exception {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        int acquire = migration.indexOf("GET_LOCK('nexion:migration:j4:drill-terminal-state', 30)");
        int candidates = migration.indexOf("CREATE TEMPORARY TABLE tmp_j4_validation_only_drill_repair AS");
        int transaction = migration.indexOf("START TRANSACTION");
        int audit = migration.indexOf("J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED");
        int commit = migration.indexOf("COMMIT");
        int release = migration.indexOf("RELEASE_LOCK('nexion:migration:j4:drill-terminal-state')");

        assertThat(acquire).isGreaterThanOrEqualTo(0);
        assertThat(transaction).isGreaterThan(acquire);
        assertThat(candidates).isGreaterThan(transaction);
        assertThat(audit).isGreaterThan(candidates);
        assertThat(commit).isGreaterThan(audit);
        assertThat(release).isGreaterThan(commit);
        assertThat(migration)
                .contains("CONSTRAINT ck_j4_drill_terminal_state_lock CHECK (acquired = 1)")
                .contains("CONSTRAINT ck_j4_drill_terminal_state_release CHECK (released = 1)")
                .contains("INSERT INTO tmp_j4_drill_terminal_state_lock_guard (acquired)")
                .contains("VALUES (@j4_drill_terminal_state_lock)")
                .contains("INSERT INTO tmp_j4_drill_terminal_state_release_guard (released)")
                .contains("VALUES (@j4_drill_terminal_state_release)")
                .doesNotContain("immutable");
    }

    private int countOccurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
