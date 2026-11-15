package ffdd.opsconsole.platform.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only operational metadata. Payload, actor and aggregate identifiers never leave the server. */
public record OutboxDiagnosticsView(long total, long unresolved, long oldestSeconds,
        boolean groupsTruncated, List<Group> groups, List<Event> rows, String nextCursor, boolean hasMore) {
    public record Group(String eventType, String status, long count, LocalDateTime oldestAt, long unresolved) {}
    public record Receipt(String status, long count) {}
    public record Event(String eventId, String eventType, String status, int retryCount,
            LocalDateTime createdAt, LocalDateTime nextRetryAt, String errorCode,
            boolean auditLinkUnresolved, List<Receipt> receipts, String userNo) {
        /** Compatibility constructor for callers that do not attach the optional authorized user number. */
        public Event(String eventId, String eventType, String status, int retryCount,
                LocalDateTime createdAt, LocalDateTime nextRetryAt, String errorCode,
                boolean auditLinkUnresolved, List<Receipt> receipts) {
            this(eventId, eventType, status, retryCount, createdAt, nextRetryAt, errorCode,
                    auditLinkUnresolved, receipts, null);
        }
    }
}
