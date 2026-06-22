package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record SupportTicketMessageView(
        Long id,
        Long ticketId,
        String ticketNo,
        Long senderId,
        String senderType,
        String senderName,
        String content,
        LocalDateTime createdAt) {
}
