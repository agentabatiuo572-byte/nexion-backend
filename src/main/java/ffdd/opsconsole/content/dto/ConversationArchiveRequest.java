package ffdd.opsconsole.content.dto;

public record ConversationArchiveRequest(
        Boolean archived,
        String reason,
        String operator) {
}
