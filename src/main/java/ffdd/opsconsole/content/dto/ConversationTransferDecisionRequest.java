package ffdd.opsconsole.content.dto;

public record ConversationTransferDecisionRequest(
        String expectedStatus,
        Long expectedVersion,
        String target,
        String reason,
        String operator) {
    public ConversationTransferDecisionRequest(String reason, String operator) {
        this("TRANSFERRED", 0L, null, reason, operator);
    }
}
