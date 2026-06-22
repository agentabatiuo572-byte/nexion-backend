package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

public record UserImpersonationSessionView(
        String sessionNo,
        Long userId,
        String userNo,
        String nickname,
        String status,
        Integer ttlMinutes,
        String operator,
        String reason,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime endedAt,
        String endedBy,
        String endReason,
        Long leftMinutes) {
}
