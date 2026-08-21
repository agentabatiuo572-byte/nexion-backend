package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dedicated canonical-event bridge for developer webhooks. This is intentionally separate from the broad
 * shared outbox scan: an unrelated event type or a slow consumer cannot starve the webhook delivery worker.
 * The shared dispatcher may also observe these rows; markPublished and delivery's unique key make that race
 * harmless and idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeveloperWebhookCanonicalOutboxDispatchScheduler {
    private static final int BATCH_SIZE = 100;
    private final EventOutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, Long> cursors = new ConcurrentHashMap<>();
    private final AtomicLong lastHeartbeatNanos = new AtomicLong();

    @Scheduled(fixedDelayString = "${nexion.developer.webhooks.canonical-dispatch-delay-ms:1000}",
            initialDelayString = "${nexion.developer.webhooks.canonical-dispatch-initial-delay-ms:1000}",
            scheduler = "developerWebhookTaskScheduler")
    public void dispatchPending() {
        logHeartbeat();
        for (String eventType : DeveloperWebhookCanonicalEventBridge.supportedCanonicalEventTypes()) {
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
                try {
                    outboxService.assertDispatchAllowed(message);
                    eventPublisher.publishEvent(message);
                    outboxService.markPublished(message.getEventId());
                } catch (RuntimeException ex) {
                    try {
                        outboxService.markFailed(message.getEventId(), ex.getMessage());
                    } catch (RuntimeException markFailure) {
                        log.error("developer webhook canonical outbox retry state update failed eventId={} error={}",
                                message.getEventId(), markFailure.getMessage());
                    }
                    log.warn("developer webhook canonical outbox dispatch failed eventId={} eventType={} error={}",
                            message.getEventId(), message.getEventType(), ex.getMessage());
                }
            }
        }
    }

    private List<EventOutboxMessage> nextBatch(String eventType) {
        long cursor = cursors.getOrDefault(eventType, 0L);
        // Read a bounded head on every pass for retry-due rows whose id is behind the cursor, and a separate
        // cursor window for newly appended rows. A permanently bad row in the head is isolated by its retry
        // due timestamp; it cannot hide fresh events behind a single global LIMIT.
        Map<String, EventOutboxMessage> merged = new LinkedHashMap<>();
        append(merged, safeRead(eventType, 0L, BATCH_SIZE / 2));
        append(merged, safeRead(eventType, cursor, BATCH_SIZE - BATCH_SIZE / 2));
        List<EventOutboxMessage> pending = new ArrayList<>(merged.values());
        if (pending != null && !pending.isEmpty()) {
            long next = pending.stream().map(EventOutboxMessage::getId).filter(java.util.Objects::nonNull)
                    .mapToLong(Long::longValue).max().orElse(cursor);
            cursors.put(eventType, Math.max(cursor, next));
        }
        return pending;
    }

    private List<EventOutboxMessage> safeRead(String eventType, long cursor, int limit) {
        try {
            return outboxService.listPendingByCanonicalType(eventType, cursor, limit);
        } catch (RuntimeException ex) {
            log.warn("developer webhook canonical outbox read failed eventType={} cursor={} error={}", eventType,
                    cursor, ex.getMessage());
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
