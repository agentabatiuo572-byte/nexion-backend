package ffdd.opsconsole.content.dto;

public record SupportTicketReplyRequest(
        String body,
        String operator,
        String reason,
        String expectedStatus,
        Long expectedVersion) {
    public SupportTicketReplyRequest(String body, String operator, String reason) {
        this(body, operator, reason, null, null);
    }
}
