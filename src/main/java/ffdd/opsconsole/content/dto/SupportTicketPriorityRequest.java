package ffdd.opsconsole.content.dto;

public record SupportTicketPriorityRequest(
        String priority,
        String operator,
        String reason,
        String expectedStatus,
        Long expectedVersion) {
    public SupportTicketPriorityRequest(String priority, String operator, String reason) {
        this(priority, operator, reason, null, null);
    }
}
