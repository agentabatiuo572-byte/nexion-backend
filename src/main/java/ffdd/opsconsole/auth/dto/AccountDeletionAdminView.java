package ffdd.opsconsole.auth.dto;

import java.time.LocalDateTime;

public record AccountDeletionAdminView(
        String requestNo,
        Long userId,
        String status,
        long version,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        LocalDateTime completedAt,
        String reason,
        String blockReason,
        boolean sessionsRevoked,
        boolean accountDisabled) {
}
