package ffdd.opsconsole.platform.dto;

/** Audited operator justification and frozen two-layer delivery state for one governed H3 threshold fact. */
public record H3OutboxRedriveRequest(
        String reason,
        Integer expectedRetryCount,
        String expectedDeliveryStatus,
        Integer expectedDeliveryAttemptCount) {
}
