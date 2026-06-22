package ffdd.opsconsole.device.domain;

import java.time.LocalDateTime;

public record DeviceTradeinTxView(
        String operation,
        String name,
        String endpoint,
        Long successCount,
        Long failureCount,
        Long rollbackCount,
        String latestKind,
        String latestStatus,
        LocalDateTime latestAt,
        String latestReason,
        Long candidateDeviceId,
        String candidateInstanceNo,
        Long candidateUserId) {
}
