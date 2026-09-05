package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppTaskAssignmentMapperContractTest {
    @Test
    void taskRuntimeUsesDurableAssignmentReceiptWalletAndServerLockTables() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/AppTaskAssignmentMapper.java"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260810_e18_task_assignment_runtime.sql"));
        assertThat(source).contains("nx_compute_task", "nx_compute_receipt", "nx_user_wallet",
                "nx_wallet_ledger", "nx_earning_event", "nx_compute_device_task_lock",
                "source_environment", "completion_nonce",
                "proof_consumed_at", "kill_init", "FOR UPDATE",
                "u.sandbox = 0", "FROM nx_user u", "UserScope userScope",
                "source_environment = 'PRODUCTION'");
        assertThat(source).contains(
                "t.source_environment = #{sourceEnvironment}",
                "source_environment = #{sourceEnvironment} AND source_environment = 'PRODUCTION'",
                "r.source_environment = t.source_environment",
                "source_environment, lock_until",
                "List<ReceiptRow> receipts",
                "List<ReceiptRow> developmentReceipts",
                "WHERE r.user_id = #{userId}",
                "t.user_device_id = r.user_device_id",
                "UPPER(t.status) = 'COMPLETED'",
                "UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')",
                "ORDER BY r.completed_at DESC, r.id DESC",
                "LIMIT #{limit} OFFSET #{offset}");
        assertThat(source).contains(
                "ROW_NUMBER() OVER",
                "PARTITION BY t.user_device_id",
                "t.device_rank <= 10",
                "UPPER(t.status) IN ('CLAIMED','RUNNING','COMPLETED')");
        assertThat(source).contains(
                "UPPER(min_vram) REGEXP '^(0|[1-9][0-9]{0,3})(GB)?$'",
                "THEN CAST(min_vram AS UNSIGNED) ELSE NULL END AS minVram",
                "LOWER(TRIM(d.product_code)) = 'cloud-share'",
                "THEN 8 ELSE d.vram_total_gb END AS deviceVram");
        assertThat(source).doesNotContain("CAST(TRIM(min_vram) AS UNSIGNED)");
        assertThat(source).doesNotContain("([[:space:]]*GB)?");
        assertThat(source).doesNotContain("AND min_vram <= #{vramTotalGb}");
        assertThat(source).doesNotContain("ORDER BY t.created_at DESC, t.id DESC LIMIT 100");
        assertThat(source).doesNotContain("insertSandboxReward", "nx_compute_sandbox_reward");
        assertThat(migration).contains("uk_compute_device_task_lock_device_env");
        assertThat(BaseMapper.class).isAssignableFrom(AppTaskAssignmentMapper.class);
    }

    @Test
    void productionAssignmentCandidatesExcludeSandboxPausedLockedAndAlreadyAssignedDevices() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/AppTaskAssignmentMapper.java"));

        assertThat(source).contains("List<AssignmentCandidate> assignmentCandidates");
        assertThat(source).contains("u.sandbox = 0");
        assertThat(source).contains("d.source_environment = 'PRODUCTION'");
        assertThat(source).contains("COALESCE(dc.dispatch_paused, 0) = 0");
        assertThat(source).contains("NOT EXISTS (SELECT 1 FROM nx_compute_task t");
        assertThat(source).contains("NOT EXISTS (SELECT 1 FROM nx_compute_device_task_lock l");
        assertThat(source).contains("d.id > #{afterDeviceId}", "ORDER BY d.id", "LIMIT #{limit}");
    }

    @Test
    void taskConfigurationIsLockedAcrossClaimAndCompletionDecisions() throws Exception {
        String eligible = String.join(" ", AppTaskAssignmentMapper.class
                .getMethod("eligibleTasks", Integer.class)
                .getAnnotation(Select.class).value());
        String runtime = String.join(" ", AppTaskAssignmentMapper.class
                .getMethod("taskRuntimeGate", Long.class, Long.class, String.class)
                .getAnnotation(Select.class).value());

        assertThat(eligible).contains("FROM nx_admin_device_task", "FOR UPDATE");
        assertThat(runtime).contains("FROM nx_admin_device_task", "FOR UPDATE");
    }

    @Test
    void settlementWritersAllowOnlyAStillActiveDeferredDeactivationToFinishItsAlreadyClaimedTask()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/AppTaskAssignmentMapper.java"));

        var settlementWriters = java.util.Arrays.stream(source.split("(?=@(?:Insert|Update))"))
                .filter(block -> block.contains("d.pending_deactivate IN (0, 1)"))
                .toList();
        assertThat(settlementWriters).hasSize(4).allSatisfy(block -> assertThat(block).contains(
                "d.deactivated_at IS NULL", "UPPER(d.ownership_status) = 'OWNED'",
                "d.source_environment='PRODUCTION'", "d.run_id=''", "d.activated_at IS NOT NULL",
                "UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')"));
        // A device marked to stop after its current task must not claim a new task.
        assertThat(source).contains("d.deactivated_at IS NULL AND d.pending_deactivate = 0");
    }
}
