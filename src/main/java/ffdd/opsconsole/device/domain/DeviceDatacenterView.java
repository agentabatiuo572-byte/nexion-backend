package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceDatacenterView(
        String dcLocation,
        String regionLabel,
        String status,
        Integer sortOrder,
        Long totalDevices,
        Long onlineDevices,
        Long pendingRecycleDevices,
        Long abnormalDevices,
        BigDecimal avgGpuUsage,
        BigDecimal avgGpuTempC,
        BigDecimal avgGpuPowerW,
        Boolean dispatchPaused,
        String pausedReason,
        LocalDateTime pausedAt,
        LocalDateTime resumedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
