package ffdd.opsconsole.content.dto;

public record ConversationStatusRequest(
        String status,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator) {
    public ConversationStatusRequest(String status, String reason, String operator) {
        this(status, inferredExpectedStatus(status), 0L, reason, operator);
    }

    public ConversationStatusRequest(String status, String expectedStatus, String reason, String operator) {
        this(status, expectedStatus, 0L, reason, operator);
    }

    private static String inferredExpectedStatus(String target) {
        return "CLOSED".equalsIgnoreCase(target) ? "RESOLVED" : "OPEN";
    }
}
