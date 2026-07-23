package ffdd.opsconsole.device.domain;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.device.dto.DeviceDatacenterUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DeviceOpsRepository {
    Map<String, Object> overviewCounters();

    PageResult<DeviceOpsView> pageDevices(DeviceOpsQueryRequest request);

    List<DeviceOpsView> listUserDevices(Long userId, int limit);

    Optional<DeviceOpsView> findDevice(Long deviceId);

    Optional<DeviceOpsView> restoreDevice(Long deviceId, LocalDateTime restoredAt);

    default long countActiveDevicesByUser(Long userId) {
        return 0;
    }

    default Optional<DeviceOpsView> activateDevice(Long deviceId, LocalDateTime activatedAt) {
        return Optional.empty();
    }

    default Optional<DeviceOpsView> deactivateE5Device(Long deviceId, boolean unbind, LocalDateTime now) {
        return Optional.empty();
    }

    default List<Long> lockE5BatchCandidateIds(Long userId, boolean pause, int limit) {
        return List.of();
    }

    default int pauseDevicesByUser(Long userId, String reason, LocalDateTime now) {
        return 0;
    }

    default int resumeDevicesByUser(Long userId, LocalDateTime now) {
        return 0;
    }

    DeviceTradeinOverviewView e3TradeinOverview(LocalDateTime since, LocalDateTime monthStart, int cliffMonth);

    Optional<DeviceOpsView> executeTradeinAction(String operation, Long deviceId, String tradeInNo, LocalDateTime now);

    Map<String, String> e3Config();

    void upsertE3Config(String key, String value, String valueType, String operator);

    List<DeviceDatacenterView> datacenterSummaries();

    Optional<DeviceDatacenterView> findDatacenter(String dcLocation);

    DeviceDatacenterView createDatacenter(DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now);

    Optional<DeviceDatacenterView> updateDatacenter(String dcLocation, DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now);

    boolean softDeleteDatacenter(String dcLocation, String operator, LocalDateTime now);

    /** 跨域引用计数:设备/待履约订单/SKU,用于删除前的硬保护校验。 */
    DatacenterReferenceCount countDatacenterReferences(String dcLocation);

    /** 软数据中心软删成功后同步清理设备表 dc_location 与运营状态行,防悬挂引用。 */
    void syncDatacenterReferencesOnDelete(String dcLocation, String operator, LocalDateTime now);

    void pauseDatacenter(String dcLocation, String reason, String operator, LocalDateTime now);

    void resumeDatacenter(String dcLocation, String operator, LocalDateTime now);
}
