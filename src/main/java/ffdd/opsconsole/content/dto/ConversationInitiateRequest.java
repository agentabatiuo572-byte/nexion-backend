package ffdd.opsconsole.content.dto;

public record ConversationInitiateRequest(
        String conversationType,
        Long userId,
        String ownerAgentId,
        String ownerAgentName,
        String openingText,
        String reason,
        String operator) {
}
