package ffdd.opsconsole.content.domain;

public record NotificationActionReceipt(
        Long userId,
        Long notificationId,
        String action,
        String route,
        String idempotencyKey) {
}
