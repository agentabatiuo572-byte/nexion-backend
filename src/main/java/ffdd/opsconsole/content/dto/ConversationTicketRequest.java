package ffdd.opsconsole.content.dto;

public record ConversationTicketRequest(
        String category,
        String priority,
        String title,
        Long assignedAdminId,
        String assignedAdminName,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator) {
    public ConversationTicketRequest(
            String category,
            String priority,
            String title,
            Long assignedAdminId,
            String assignedAdminName,
            String reason,
            String operator) {
        this(category, priority, title, assignedAdminId, assignedAdminName, "OPEN", 0L, reason, operator);
    }
}
