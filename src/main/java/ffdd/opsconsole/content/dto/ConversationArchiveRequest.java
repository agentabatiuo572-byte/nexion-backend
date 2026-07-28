package ffdd.opsconsole.content.dto;

public record ConversationArchiveRequest(
        Boolean archived,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator) {
    public ConversationArchiveRequest(Boolean archived, String reason, String operator) {
        this(archived, Boolean.FALSE.equals(archived) ? "CLOSED" : "RESOLVED", 0L, reason, operator);
    }

    public ConversationArchiveRequest(Boolean archived, String expectedStatus, String reason, String operator) {
        this(archived, expectedStatus, 0L, reason, operator);
    }
}
