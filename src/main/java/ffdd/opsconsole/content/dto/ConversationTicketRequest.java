package ffdd.opsconsole.content.dto;

public record ConversationTicketRequest(
        String category,
        String priority,
        String title,
        Long assignedAdminId,
        String assignedAdminName,
        String reason,
        String operator) {
}
