package ffdd.opsconsole.content.dto;

public record ConversationStatusRequest(
        String status,
        String reason,
        String operator) {
}
