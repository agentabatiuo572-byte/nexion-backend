package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

public record UserAccountListEntryView(
        Long userId,
        String userNo,
        String nickname,
        String kind,
        String reason,
        String status,
        LocalDateTime expiresAt,
        String createdBy,
        LocalDateTime createdAt,
        String releasedBy,
        String releaseReason,
        LocalDateTime releasedAt) {
}
