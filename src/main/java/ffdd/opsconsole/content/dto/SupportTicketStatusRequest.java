package ffdd.opsconsole.content.dto;

public record SupportTicketStatusRequest(
        String status,
        String operator,
        String reason,
        String expectedStatus,
        Long expectedVersion) {
    public SupportTicketStatusRequest(String status, String operator, String reason) {
        this(status, operator, reason, null, null);
    }
}
