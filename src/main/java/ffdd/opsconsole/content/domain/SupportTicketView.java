package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/** Read projection of a historical (retired) support ticket row. */
public record SupportTicketView(
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
        Boolean archived,
        LocalDateTime archivedAt,
        Long version,
        Boolean userExists,
        String sourceConversationNo,
        boolean contentRestricted) {
}
