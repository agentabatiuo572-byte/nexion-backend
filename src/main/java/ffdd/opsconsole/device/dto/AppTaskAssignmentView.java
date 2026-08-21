package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AppTaskAssignmentView(
        String taskNo,
        Long deviceId,
        String taskId,
        String taskName,
        String taskClass,
        String model,
        String client,
        String status,
        BigDecimal rewardUsdt,
        Integer requiredSeconds,
        LocalDateTime startedAt,
        LocalDateTime completableAt,
        LocalDateTime completedAt,
        String receiptNo,
        String proofNonce,
        LocalDateTime proofExpiresAt,
        String source,
        String sourceEnvironment,
        String runId,
        boolean serverCanonical) {
}
