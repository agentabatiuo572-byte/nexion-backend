package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.shared.outbox.ComputeOutboxDispatchScheduler;
import ffdd.opsconsole.shared.outbox.EventOutboxDispatcher;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dedicated canonical-event bridge for developer webhooks. This is intentionally separate from the broad
 * shared outbox scan: an unrelated event type or a slow consumer cannot starve the webhook delivery worker.
 * The shared dispatcher may also observe these rows; both use the same per-event dispatch lock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeveloperWebhookCanonicalOutboxDispatchScheduler {
    private static final int BATCH_SIZE = 100;
    private final EventOutboxService outboxService;
    private final EventOutboxDispatcher dispatcher;
    private final Map<String, Long> cursors = new ConcurrentHashMap<>();
    private final AtomicLong lastHeartbeatNanos = new AtomicLong();

    @Scheduled(fixedDelayString = "${nexion.developer.webhooks.canonical-dispatch-delay-ms:1000}",
            initialDelayString = "${nexion.developer.webhooks.canonical-dispatch-initial-delay-ms:1000}",
            scheduler = "developerWebhookTaskScheduler")
    public void dispatchPending() {
        logHeartbeat();
        for (String eventType : DeveloperWebhookCanonicalEventBridge.supportedCanonicalEventTypes()) {
            if (ComputeOutboxDispatchScheduler.EVENT_TYPES.contains(eventType)) continue;
            List<EventOutboxMessage> pending;
            try {
                pending = nextBatch(eventType);
            } catch (RuntimeException ex) {
                log.warn("developer webhook canonical outbox read failed eventType={} error={}", eventType,
                        ex.getMessage());
                continue;
            }
            if (pending == null || pending.isEmpty()) continue;
            for (EventOutboxMessage message : pending) {
                dispatcher.dispatch(message, null);
            }
        }
    }

    private List<EventOutboxMessage> nextBatch(String eventType) {
        Map<String, EventOutboxMessage> merged = new LinkedHashMap<>();
        // The head always makes room for older facts and retries behind the cursor.
        append(merged, safeRead(eventType, 0L, BATCH_SIZE / 2));
        long cursor = cursors.getOrDefault(eventType, 0L);
        append(merged, safeRead(eventType, cursor, BATCH_SIZE - BATCH_SIZE / 2));
        long next = merged.values().stream().map(EventOutboxMessage::getId).filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue).max().orElse(cursor);
        if (next > cursor) cursors.put(eventType, next);
        return new ArrayList<>(merged.values());
    }

    private List<EventOutboxMessage> safeRead(String eventType, long cursor, int limit) {
        try {
            return outboxService.listPendingByCanonicalType(eventType, cursor, limit);
        } catch (RuntimeException ex) {
            log.warn("developer webhook canonical outbox read failed eventType={} cursor={} error={}",
                    eventType, cursor, ex.getMessage());
            return List.of();
        }
    }

    private void append(Map<String, EventOutboxMessage> merged, List<EventOutboxMessage> rows) {
        if (rows == null) return;
        for (EventOutboxMessage row : rows) {
            String key = row.getEventId() == null ? "id:" + row.getId() : row.getEventId();
            merged.putIfAbsent(key, row);
        }
    }

    private void logHeartbeat() {
        long now = System.nanoTime();
        long previous = lastHeartbeatNanos.get();
        if (previous == 0 || now - previous >= 30_000_000_000L) {
            if (lastHeartbeatNanos.compareAndSet(previous, now)) {
                log.info("developer webhook canonical dispatcher heartbeat scheduled=true");
            }
        }
    }
}
