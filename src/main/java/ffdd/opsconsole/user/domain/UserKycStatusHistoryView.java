package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

public record UserKycStatusHistoryView(
        String beforeStatus,
        String afterStatus,
        String reasonCode,
        String reason,
        String evidenceRef,
        String source,
        String operator,
        String ticketId,
        LocalDateTime createdAt) {
}
