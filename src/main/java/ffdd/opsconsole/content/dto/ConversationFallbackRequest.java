package ffdd.opsconsole.content.dto;

public record ConversationFallbackRequest(
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator) {
    public ConversationFallbackRequest(String reason, String operator) {
        this("TRANSFERRED", 0L, reason, operator);
    }
}
