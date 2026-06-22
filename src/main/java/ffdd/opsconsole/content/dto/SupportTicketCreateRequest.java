package ffdd.opsconsole.content.dto;

public record SupportTicketCreateRequest(
        Long userId,
        String category,
        String priority,
        String title,
        String body,
        Long assignedAdminId,
        String assignedAdminName,
        String operator,
        String reason) {
}
