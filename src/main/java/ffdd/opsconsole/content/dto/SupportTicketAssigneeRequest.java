package ffdd.opsconsole.content.dto;

public record SupportTicketAssigneeRequest(
        Long assignedAdminId,
        String assignedAdminName,
        String operator,
        String reason,
        String expectedStatus,
        Long expectedVersion) {
    public SupportTicketAssigneeRequest(
            Long assignedAdminId,
            String assignedAdminName,
            String operator,
            String reason) {
        this(assignedAdminId, assignedAdminName, operator, reason, null, null);
    }
}
