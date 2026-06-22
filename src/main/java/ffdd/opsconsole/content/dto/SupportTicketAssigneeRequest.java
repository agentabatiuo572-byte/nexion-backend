package ffdd.opsconsole.content.dto;

public record SupportTicketAssigneeRequest(
        Long assignedAdminId,
        String assignedAdminName,
        String operator,
        String reason) {
}
