package ffdd.opsconsole.content.dto;

public record SupportTicketReplyRequest(
        String body,
        String operator,
        String reason) {
}
