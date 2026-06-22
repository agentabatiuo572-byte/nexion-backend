package ffdd.opsconsole.content.dto;

public record ConversationReplyRequest(
        String body,
        String reason,
        String operator) {
}
