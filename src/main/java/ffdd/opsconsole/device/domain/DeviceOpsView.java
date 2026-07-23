package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceOpsView(
        Long id,
        Long userId,
        String userNo,
        String nickname,
        String instanceNo,
        String name,
        String productTier,
        String productCode,
        String status,
        String dcLocation,
        BigDecimal hashrate,
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        LocalDateTime lastSeenAt,
        LocalDateTime purchasedAt,
        LocalDateTime activatedAt,
        LocalDateTime deactivatedAt,
        String baseRate,
        BigDecimal currentEfficiency,
        Integer pendingDeactivate,
        String runtimeStatus,
        BigDecimal gpuUsage,
        BigDecimal gpuTempC,
        BigDecimal gpuPowerW,
        String pausedReason,
        String activeTaskNo,
        LocalDateTime heartbeatAt,
        Integer batteryLevel,
        Integer isCharging,
        Integer networkReachable,
        String thermalState,
        Long activeDevicesForUser,
        Long userDeviceSlotNo) {

    public DeviceOpsView(
            Long id, Long userId, String userNo, String nickname, String instanceNo, String name,
            String productTier, String productCode, String status, String dcLocation,
            BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex,
            LocalDateTime lastSeenAt, LocalDateTime activatedAt, LocalDateTime deactivatedAt,
            Integer pendingDeactivate, String runtimeStatus, BigDecimal gpuUsage,
            BigDecimal gpuTempC, BigDecimal gpuPowerW, String pausedReason,
            String activeTaskNo, LocalDateTime heartbeatAt, Long userDeviceSlotNo) {
        this(id, userId, userNo, nickname, instanceNo, name, productTier, productCode, status,
                dcLocation, hashrate, dailyUsdt, dailyNex, lastSeenAt, null, activatedAt,
                deactivatedAt, null, BigDecimal.ONE, pendingDeactivate, runtimeStatus, gpuUsage,
                gpuTempC, gpuPowerW, pausedReason, activeTaskNo, heartbeatAt, null, null, null,
                null, 0L, userDeviceSlotNo);
    }
}
