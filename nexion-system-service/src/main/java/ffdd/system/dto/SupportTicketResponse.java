package ffdd.system.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SupportTicketResponse(
        Long id,
        String ticketNo,
        Long userId,
        String category,
        String priority,
        String status,
        String title,
        String lastMessage,
        Long assignedAdminId,
        String assignedAdminName,
        Integer userUnreadCount,
        Integer opsUnreadCount,
        Integer messageCount,
        LocalDateTime lastMessageAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<SupportTicketMessageResponse> messages) {
}
