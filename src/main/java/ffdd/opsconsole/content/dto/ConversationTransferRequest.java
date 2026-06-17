package ffdd.opsconsole.content.dto;

public record ConversationTransferRequest(
        String targetType,
        String targetId,
        String targetName,
        String reason,
        String operator) {
}
