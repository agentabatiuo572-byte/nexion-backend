package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

public record UserSessionView(
        Long userId,
        String refreshTokenId,
        String deviceName,
        String clientIpMasked,
        String status,
        LocalDateTime issuedAt,
        LocalDateTime lastActiveAt,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt) {
    public UserSessionView(
            Long userId, String refreshTokenId, String deviceName, String clientIpMasked,
            String status, LocalDateTime issuedAt, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        this(userId, refreshTokenId, deviceName, clientIpMasked, status,
                issuedAt, issuedAt, expiresAt, revokedAt);
    }
}
