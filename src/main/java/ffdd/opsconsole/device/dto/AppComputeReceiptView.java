package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AppComputeReceiptView(
        String receiptNo,
        String taskNo,
        Long deviceId,
        String deviceInstanceNo,
        String deviceName,
        String deviceType,
        String deviceGpu,
        Integer vramTotalGb,
        String taskId,
        String taskName,
        String taskClass,
        String model,
        String client,
        BigDecimal rewardUsdt,
        BigDecimal rewardNex,
        String earningStatus,
        String proofHash,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Integer durationSec,
        String source,
        String sourceEnvironment,
        String runId,
        boolean serverCanonical) {
}
