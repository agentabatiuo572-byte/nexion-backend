package ffdd.opsconsole.content.dto;

public record SessionReplyTemplateStatusRequest(
        String status,
        String expectedStatus,
        String operator,
        String reason) {
    public SessionReplyTemplateStatusRequest(String status, String operator, String reason) {
        this(status, null, operator, reason);
    }
}
