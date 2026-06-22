package ffdd.opsconsole.content.dto;

public record SupportTicketPriorityRequest(
        String priority,
        String operator,
        String reason) {
}
