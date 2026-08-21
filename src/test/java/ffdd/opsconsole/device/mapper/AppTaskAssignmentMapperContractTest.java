package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
                "proof_consumed_at", "kill_init", "min_vram <= #{vramTotalGb}", "FOR UPDATE",
                "u.sandbox = 0", "FROM nx_user u", "UserScope userScope",
                "source_environment = 'PRODUCTION'");
        assertThat(source).contains(
                "t.source_environment = #{sourceEnvironment}",
                "source_environment = #{sourceEnvironment} AND source_environment = 'PRODUCTION'",
                "r.source_environment = t.source_environment",
                "source_environment, lock_until");
        assertThat(source).doesNotContain("insertSandboxReward", "nx_compute_sandbox_reward");
        assertThat(migration).contains("uk_compute_device_task_lock_device_env");
        assertThat(BaseMapper.class).isAssignableFrom(AppTaskAssignmentMapper.class);
    }
}
