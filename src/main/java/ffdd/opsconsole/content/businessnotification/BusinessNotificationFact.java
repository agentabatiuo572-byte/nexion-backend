package ffdd.opsconsole.content.businessnotification;

/** A committed, server-owned fact; never contains credentials, customer message text or amounts. */
public record BusinessNotificationFact(String factKey, Long userId, String category, String messageKey, String route,
        java.time.LocalDateTime occurredAt) {}
