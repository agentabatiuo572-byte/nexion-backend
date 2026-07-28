package ffdd.opsconsole.content.dto;

public record NotificationCapUpdateRequest(
        String cap,
        String expectedCap,
        String operator,
        String reason) {
}
