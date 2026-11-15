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
        String deliveryLastError,
        String userNo) {
    /** Compatibility constructor for callers that do not attach the optional authorized user number. */
    public H3OutboxRedriveView(String eventId, String eventType, String status, int retryCount,
            String lastError, String deliveryStatus, int deliveryAttemptCount, String deliveryLastError) {
        this(eventId, eventType, status, retryCount, lastError, deliveryStatus,
                deliveryAttemptCount, deliveryLastError, null);
    }

    /** Never expose an authorized user number in a replayable POST receipt. */
    public H3OutboxRedriveView withoutUserNo() {
        return userNo == null ? this : new H3OutboxRedriveView(eventId, eventType, status, retryCount,
                lastError, deliveryStatus, deliveryAttemptCount, deliveryLastError, null);
    }
}
