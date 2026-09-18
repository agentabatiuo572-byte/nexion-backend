package ffdd.opsconsole.platform.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only operational metadata. Deliberately excludes payload, actor and aggregate identifiers. */
public record OutboxDiagnosticsView(long total, long unresolved, long oldestSeconds,
        boolean groupsTruncated, List<Group> groups, List<Event> rows, String nextCursor, boolean hasMore) {
    public record Group(String eventType, String status, long count, LocalDateTime oldestAt, long unresolved) {}
    public record Receipt(String status, long count) {}
    public record Event(String eventId, String eventType, String status, int retryCount,
            LocalDateTime createdAt, LocalDateTime nextRetryAt, String errorCode,
            boolean auditLinkUnresolved, List<Receipt> receipts) {}
}
