package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

/** Server-canonical K5 decision that may authorize exactly one C5 high-risk action. */
public record UserKycReverificationView(
        String action,
        String ticketId,
        String status,
        String verifiedBy,
        LocalDateTime verifiedAt,
        LocalDateTime expiresAt) {
}
