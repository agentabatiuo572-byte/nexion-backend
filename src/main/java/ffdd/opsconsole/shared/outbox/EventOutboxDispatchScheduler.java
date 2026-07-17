package ffdd.opsconsole.shared.outbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers durable outbox messages into the modular-monolith event bus. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventOutboxDispatchScheduler {
    private static final int BATCH_SIZE = 100;
    static final String SUPPORTED_EVENT_TYPE = "ADMIN_KILLSWITCH_TOGGLED";
    static final String TAMPER_EVENT_TYPE = "RISK_TAMPER_DETECTED";
    static final String TAMPER_CONFIG_EVENT_TYPE = "ADMIN_J3_TAMPER_CONFIG_CHANGED";

    private final EventOutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(
            fixedDelayString = "${nexion.outbox.dispatch-delay-ms:1000}",
            initialDelayString = "${nexion.outbox.dispatch-initial-delay-ms:1000}")
    public void dispatchPending() {
        // Only dispatch event types that have a synchronous, durable consumer.
        // Publishing an unknown Spring event succeeds even when it has no listener;
        // selecting all event types here would therefore falsely mark them PUBLISHED.
        for (String eventType : List.of(SUPPORTED_EVENT_TYPE, TAMPER_EVENT_TYPE, TAMPER_CONFIG_EVENT_TYPE)) {
            List<EventOutboxMessage> pending = outboxService.listPendingByEventType(eventType, BATCH_SIZE);
            if (pending == null || pending.isEmpty()) {
                continue;
            }
            for (EventOutboxMessage message : pending) {
                try {
                    eventPublisher.publishEvent(message);
                    if (!outboxService.markPublished(message.getEventId())) {
                        log.warn("Outbox message was delivered but status was not updated eventId={}", message.getEventId());
                    }
                } catch (RuntimeException ex) {
                    outboxService.markFailed(message.getEventId(), ex.getMessage());
                    log.warn("Outbox delivery failed eventId={}, eventType={}, error={}",
                            message.getEventId(), message.getEventType(), ex.getMessage());
                }
            }
        }
    }
}
