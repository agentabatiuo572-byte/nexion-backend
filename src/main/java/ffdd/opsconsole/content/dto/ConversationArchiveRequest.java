package ffdd.opsconsole.content.dto;

public record ConversationArchiveRequest(
        Boolean archived,
        String expectedStatus,
        String reason,
        String operator) {
    public ConversationArchiveRequest(Boolean archived, String reason, String operator) {
        this(archived, null, reason, operator);
    }
}
