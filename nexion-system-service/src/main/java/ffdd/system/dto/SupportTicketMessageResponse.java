package ffdd.system.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SupportTicketMessageResponse(
        Long id,
        String ticketNo,
        Long senderId,
        String senderType,
        String senderName,
        String content,
        LocalDateTime createdAt,
        List<SupportTicketAttachmentResponse> attachments) {
}
