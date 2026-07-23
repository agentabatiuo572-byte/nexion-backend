package ffdd.opsconsole.content.dto;

public record ConversationStatusRequest(
        String status,
        String expectedStatus,
        String reason,
        String operator) {
    public ConversationStatusRequest(String status, String reason, String operator) {
        this(status, null, reason, operator);
    }
}
