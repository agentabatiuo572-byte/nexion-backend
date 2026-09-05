package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E5ActivationAndDeactivationSqlContractTest {
    @Test
    void e5ActivationSerializesTheUserBeforeCheckingTheConfiguredSlotCap() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/DeviceOpsMapper.java"));
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));

        assertThat(mapper).contains("lockUserForE5Activation", "FOR UPDATE");
        assertThat(service).contains("lockUserForE5Activation", "DEVICE_ACTIVATION_LOCK_UNAVAILABLE",
                "countActiveDevicesByUser");
    }

    @Test
    void e5DeactivationDefersUntilActiveTaskSettlementAndNeverCancelsTheTask() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/DeviceOpsMapper.java"));
        String repository = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/infrastructure/MybatisDeviceOpsRepository.java"));
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));

        assertThat(mapper)
                .contains("pending_deactivate", "nx_compute_task", "'CLAIMED','RUNNING'")
                .doesNotContain("cancelActiveTasksForE5Deactivation", "t.status='CANCELLED'");
        assertThat(repository).doesNotContain("cancelActiveTasksForE5Deactivation");
        assertThat(service).doesNotContain("cancelActiveTasksForE5Deactivation");
    }

    @Test
    void e5SlotProjectionExcludesComputeShareRowsFromPhysicalDeviceCapacity() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/DeviceOpsMapper.java"));

        assertThat(mapper)
                .contains("AS activeDevicesForUser", "AS userDeviceSlotNo")
                .contains("UPPER(COALESCE(NULLIF(a.device_type,''),'DEVICE')) != 'SHARE'")
                .contains("UPPER(COALESCE(NULLIF(s.device_type,''),'DEVICE')) != 'SHARE'")
                .contains("WHEN UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) = 'SHARE' THEN NULL");
    }
}
