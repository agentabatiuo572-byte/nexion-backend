package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt that closes the D1 top-up lifecycle hand-off to analytics consumers. */
@Component
@RequiredArgsConstructor
public class D1TopupLifecycleEventConsumer {
    static final String CONSUMER_GROUP = "d1-topup-lifecycle-observer";
    static final String TOPIC = "spring-local-d1-topup-lifecycle";
    private static final Set<String> EVENT_TYPES = Set.of(
            "wallet.topup_initiated",
            "wallet.topup_confirmed");

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPES.contains(message.getEventType())
                || !"WALLET".equals(message.getAggregateType())) {
            return;
        }
        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
        if (claim.claimed()) {
            try {
                deliveryService.markSuccess(CONSUMER_GROUP, claim.eventId(), 1);
            } catch (RuntimeException ex) {
                deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
                throw ex;
            }
            return;
        }
        if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
            throw new IllegalStateException("D1_TOPUP_LIFECYCLE_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}
