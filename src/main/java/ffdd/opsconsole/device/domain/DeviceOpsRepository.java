package ffdd.opsconsole.device.domain;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface DeviceOpsRepository {
    Map<String, Object> overviewCounters();

    /** Durable device operator events, latest 50 within the last 24 hours. */
    default Map<String, Object> e5Observability() {
        return Map.of();
    }

    PageResult<DeviceOpsView> pageDevices(DeviceOpsQueryRequest request, boolean phones);

    Set<Long> legacyPendingDeviceIds(List<Long> deviceIds);

    PageResult<DeviceOpsView> pageUserDevices(Long userId, long page, long size);

    List<DeviceOpsView> listUserDevices(Long userId, int limit);

    Optional<DeviceOpsView> findDevice(Long deviceId);

    Optional<DeviceOpsView> restoreDevice(Long deviceId, LocalDateTime restoredAt);

    default long countActiveDevicesByUser(Long userId) {
        return 0;
    }

    /** Every activated device plus order, promotion and trial reservations; called with the owner lock held. */
    long countOccupiedSlotsForE5Activation(Long userId);

    /** Serializes E5 activations for one owner before capacity is read. */
    default boolean lockUserForE5Activation(Long userId) {
        return false;
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

    default int resumeDevicesByUser(Long userId, List<Long> deviceIds, LocalDateTime now) {
        return 0;
    }

    void syncSimulatedPause(Long userId, List<Long> deviceIds, boolean pause, LocalDateTime now);

    DeviceTradeinOverviewView e3TradeinOverview(LocalDateTime since, LocalDateTime monthStart, int cliffMonth);

    Optional<DeviceOpsView> executeTradeinAction(String operation, Long deviceId, String tradeInNo, LocalDateTime now);

    Map<String, String> e3Config();

    void upsertE3Config(String key, String value, String valueType, String operator);

}
