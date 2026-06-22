package ffdd.opsconsole.content.dto;

public record SupportTicketStatusRequest(
        String status,
        String operator,
        String reason) {
}
