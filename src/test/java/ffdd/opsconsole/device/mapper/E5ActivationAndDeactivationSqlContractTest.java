package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E5ActivationAndDeactivationSqlContractTest {
    @Test
    void e5ActivationSerializesTheUserBeforeCheckingTheFixedSixSlotCap() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/DeviceOpsMapper.java"));
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));
        String activation = service.substring(service.indexOf("public ApiResult<DeviceOpsView> activateE5Device("),
                service.indexOf("public ApiResult<DeviceOpsView> deactivateE5Device("));

        assertThat(mapper).contains("lockUserForE5Activation", "FOR UPDATE");
        assertThat(activation).contains("lockUserForE5Activation", "DEVICE_ACTIVATION_LOCK_UNAVAILABLE",
                "countOccupiedSlotsForE5Activation", "countOccupiedSlotsForE5Activation(before.userId()) >= slotCap")
                .doesNotContain("occupiesPhysicalSlot");
        assertThat(service).containsPattern("private int e5MaxDevicesPerUser\\(\\) \\{\\s*return 6;\\s*\\}");
        assertThat(activation.indexOf("lockUserForE5Activation"))
                .isLessThan(activation.indexOf("countOccupiedSlotsForE5Activation"));
    }

    @Test
    void e5CapacityKeepsOrderPromotionAndTrialReservationsInTheSameTotal() throws Exception {
        String repository = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/infrastructure/MybatisDeviceOpsRepository.java"));
        String canonical = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"));
        int start = canonical.indexOf("default long lockOccupiedDeviceSlotCount(");
        String occupied = canonical.substring(start, canonical.indexOf("@Select", start));

        assertThat(repository).contains("return canonicalStateMapper.lockOccupiedDeviceSlotCount(userId, sandbox)");
        assertThat(occupied).contains("lockActiveDeviceCount(userId, sandbox)",
                "+ lockReservedDeviceOrderCount(userId, sandbox)", "+ (sandbox ? 0 : lockReservedTrialSlotCount(userId))");
        assertThat(CanonicalStateMapper.LOCK_RESERVED_DEVICE_ORDERS)
                .contains("SUM(order_item.quantity)", "nx_promotion_reservation", "'RESERVED','COMMITTED'", "FOR SHARE")
                .doesNotContain("product_type", "THEN 0");
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
    void e5SlotProjectionCountsCloudShareAndEveryOtherActivatedDevice() {
        assertThat(DeviceOpsMapper.DEVICE_COLUMNS)
                .contains("AS activeDevicesForUser", "AS userDeviceSlotNo")
                .contains("a.user_id=d.user_id", "s.user_id = d.user_id",
                        "a.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')",
                        "s.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')",
                        "a.ownership_status = 'OWNED'", "s.ownership_status = 'OWNED'",
                        "a.activated_at IS NOT NULL", "s.activated_at IS NOT NULL",
                        "a.deactivated_at IS NULL", "s.deactivated_at IS NULL", "NOT s.id > d.id")
                .doesNotContain("device_type", "'SHARE'", "a.pending_deactivate", "s.pending_deactivate", "d.pending_deactivate = 1");
    }
}
