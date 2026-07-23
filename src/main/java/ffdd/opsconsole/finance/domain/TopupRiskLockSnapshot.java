package ffdd.opsconsole.finance.domain;

import java.time.LocalDateTime;

public record TopupRiskLockSnapshot(
        String targetType,
        String targetValue,
        String status,
        String source,
        String reason,
        LocalDateTime lockedUntil,
        boolean active) {
}
