package ffdd.opsconsole.content.dto;

public record SupportTicketArchiveRequest(
        Boolean archived,
        String operator,
        String reason) {
}
