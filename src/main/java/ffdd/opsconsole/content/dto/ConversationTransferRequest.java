package ffdd.opsconsole.content.dto;

public record ConversationTransferRequest(
        String targetType,
        String targetId,
        String targetName,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator) {
    public ConversationTransferRequest(String targetType, String targetId, String targetName, String reason, String operator) {
        this(targetType, targetId, targetName, "OPEN", 0L, reason, operator);
    }
}
