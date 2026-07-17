package ffdd.opsconsole.emergency.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Claims a durable J3 delivery before starting the projection transaction. A failed projection is
 * rolled back independently, then the FAILED/DEAD receipt is committed against the durable claim.
 */
@Component
@RequiredArgsConstructor
public class TamperDetectedEventConsumer {
    static final String CONSUMER_GROUP = "j3-tamper-projection";
    static final String EVENT_TYPE = "RISK_TAMPER_DETECTED";
    static final String TOPIC = "spring-local-j3-projection";

    private final EventConsumerDeliveryService deliveryService;
    private final TamperDetectedEventProjector projector;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || !EVENT_TYPE.equals(message.getEventType())) {
            return;
        }
        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
        if (!claim.claimed()) {
            if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
                throw new IllegalStateException("TAMPER_EVENT_DELIVERY_NOT_COMPLETE:" + claim.status());
            }
            return;
        }
        try {
            projector.project(message, claim.eventId());
        } catch (RuntimeException ex) {
            deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
            throw ex;
        }
    }
}
