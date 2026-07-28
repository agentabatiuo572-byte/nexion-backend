package ffdd.opsconsole.content.dto;

public record ConversationReplyRequest(
        String body,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator) {
    public ConversationReplyRequest(String body, String reason, String operator) {
        this(body, "OPEN", 0L, reason, operator);
    }
}
