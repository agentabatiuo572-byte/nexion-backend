package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeviceForceDeactivationContractTest {
    @Test
    void taskClaimAndCompletionUseTheSameUserDeviceTaskLockOrderAsDeactivation() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/application/AppTaskAssignmentService.java"));
        String complete = source.substring(source.indexOf("private ApiResult<AppTaskAssignmentView> completeInternal"),
                source.indexOf("private void validateDevice"));
        assertThat(complete).contains("lockProductionUser(userId)", "mapper.assignmentDeviceId(userId, taskNo, sourceEnvironment)");
        assertThat(complete.indexOf("lockProductionUser(userId)"))
                .isLessThan(complete.indexOf("mapper.lockOwnedDevice"));
        assertThat(complete.indexOf("mapper.lockOwnedDevice"))
                .isLessThan(complete.indexOf("mapper.lockAssignment"));
        String claim = source.substring(source.indexOf("private ApiResult<AppTaskAssignmentView> claimInternal"),
                source.indexOf("private ApiResult<AppTaskAssignmentView> completeInternal"));
        assertThat(claim).contains("lockProductionUser(userId)");
        assertThat(claim.indexOf("lockProductionUser(userId)"))
                .isLessThan(claim.indexOf("mapper.lockOwnedDevice"));
    }

    @Test
    void terminatesOnlyOwnedProductionActiveTasksBeforePublishingTheEvent() throws Exception {
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryService.java"));
        int start = service.indexOf("private ApiResult<Map<String, Object>> deactivateDeviceInternal");
        String deactivate = service.substring(start, service.indexOf("public ApiResult<Map<String, Object>> deviceEarnings", start));
        assertThat(deactivate).contains("mapper.cancelActiveDeviceTasks(userId, deviceId)");
        assertThat(deactivate.indexOf("mapper.cancelActiveDeviceTasks"))
                .isGreaterThan(deactivate.indexOf("mapper.deactivateOwnedDeviceCas"))
                .isLessThan(deactivate.indexOf("outboxService.publishUserEvent"));
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"));
        int end = mapper.indexOf("int cancelActiveDeviceTasks");
        String sql = mapper.substring(mapper.lastIndexOf("@Update", end), end);
        assertThat(sql).contains("t.status = 'CANCELLED'", "t.user_id = #{userId}",
                "t.user_device_id = #{deviceId}", "t.source_environment = 'PRODUCTION'",
                "d.source_environment = 'PRODUCTION'", "d.run_id = ''",
                "('CLAIMED','RUNNING')", "t.proof_consumed_at = NOW()", "u.sandbox = 0");
        assertThat(sql).doesNotContain("reward_usdt =", "nx_user_wallet", "nx_wallet_ledger");
    }
}
