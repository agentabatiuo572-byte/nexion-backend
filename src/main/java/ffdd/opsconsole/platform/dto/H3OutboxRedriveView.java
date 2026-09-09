package ffdd.opsconsole.platform.dto;

/** Safe redrive result: source/payload fields intentionally never leave the server. */
public record H3OutboxRedriveView(
        String eventId,
        String eventType,
        String status,
        int retryCount,
        String lastError,
        String deliveryStatus,
        int deliveryAttemptCount,
        String deliveryLastError) {
}
