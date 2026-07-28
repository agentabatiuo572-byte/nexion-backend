package ffdd.opsconsole.content.dto;

public record SupportTicketNoteRequest(
        String body,
        String operator,
        String reason,
        String expectedStatus,
        Long expectedVersion) {
}
