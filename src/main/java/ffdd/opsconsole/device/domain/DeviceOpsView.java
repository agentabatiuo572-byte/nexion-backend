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
        LocalDateTime activatedAt,
        LocalDateTime deactivatedAt,
        Integer pendingDeactivate,
        String runtimeStatus,
        BigDecimal gpuUsage,
        BigDecimal gpuTempC,
        BigDecimal gpuPowerW,
        String pausedReason,
        String activeTaskNo,
        LocalDateTime heartbeatAt) {
}
