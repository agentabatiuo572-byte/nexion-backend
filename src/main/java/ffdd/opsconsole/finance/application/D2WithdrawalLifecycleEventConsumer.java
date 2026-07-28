package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt for D2 withdrawal lifecycle events exposed to A4 and analytics. */
@Component
@RequiredArgsConstructor
public class D2WithdrawalLifecycleEventConsumer {
    static final String CONSUMER_GROUP = "d2-withdrawal-lifecycle-observer";
    static final String TOPIC = "spring-local-d2-withdrawal-lifecycle";
    private static final Set<String> EVENT_TYPES = Set.of(
            "withdraw.submitted",
            "withdraw.approved",
            "withdraw.rejected",
            "withdraw.delayed",
            "withdraw.frozen",
            "withdraw.unfrozen",
            "withdraw.refunded",
            "withdraw.review_due",
            "withdraw.confirmed");

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPES.contains(message.getEventType())
                || !"WITHDRAWAL".equals(message.getAggregateType())) {
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
            throw new IllegalStateException("D2_WITHDRAWAL_LIFECYCLE_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}
