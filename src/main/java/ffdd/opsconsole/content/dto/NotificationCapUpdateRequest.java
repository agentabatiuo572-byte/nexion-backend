package ffdd.opsconsole.content.dto;

public record NotificationCapUpdateRequest(
        String cap,
        String operator,
        String reason) {
}
