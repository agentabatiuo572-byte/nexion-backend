package ffdd.opsconsole.content.domain;

public record NotificationActionResult(
        Long notificationId,
        String action,
        String route,
        boolean recorded) {
}
