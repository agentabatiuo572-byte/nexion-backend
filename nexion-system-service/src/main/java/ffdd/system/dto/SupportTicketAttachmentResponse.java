package ffdd.system.dto;

import java.time.LocalDateTime;

public record SupportTicketAttachmentResponse(
        Long id,
        String objectKey,
        String fileName,
        String contentType,
        Long fileSize,
        LocalDateTime createdAt) {
}
