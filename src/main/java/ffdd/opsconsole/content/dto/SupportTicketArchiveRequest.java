package ffdd.opsconsole.content.dto;

public record SupportTicketArchiveRequest(
        Boolean archived,
        String operator,
        String reason,
        String expectedStatus,
        Long expectedVersion) {
    public SupportTicketArchiveRequest(Boolean archived, String operator, String reason) {
        this(archived, operator, reason, null, null);
    }
}
