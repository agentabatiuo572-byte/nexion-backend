package ffdd.opsconsole.device.domain;

import ffdd.opsconsole.shared.api.PageResult;
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

    DeviceTradeinOverviewView e3TradeinOverview(LocalDateTime since, LocalDateTime monthStart, int cliffMonth);

    Optional<DeviceOpsView> executeTradeinAction(String operation, Long deviceId, String tradeInNo, LocalDateTime now);

    Map<String, String> e3Config();

    void upsertE3Config(String key, String value, String valueType, String operator);

    List<Map<String, Object>> datacenterSummaries();

    void pauseDatacenter(String dcLocation, String reason, String operator, LocalDateTime now);

    void resumeDatacenter(String dcLocation, String operator, LocalDateTime now);
}
