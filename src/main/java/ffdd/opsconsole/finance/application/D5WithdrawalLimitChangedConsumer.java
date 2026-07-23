package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable delivery receipt used by D2/A4 to prove that a D5 configuration event was consumed. */
@Component
@RequiredArgsConstructor
public class D5WithdrawalLimitChangedConsumer {
    static final String CONSUMER_GROUP = "d5-withdrawal-limit-observer";
    static final String TOPIC = "spring-local-d5-withdrawal-limit";
    private static final String EVENT_TYPE = "admin.withdraw_limit_changed";
    private static final String AGGREGATE_TYPE = "WITHDRAWAL_PARAM";

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPE.equals(message.getEventType())
                || !AGGREGATE_TYPE.equals(message.getAggregateType())) {
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
            throw new IllegalStateException("D5_WITHDRAWAL_LIMIT_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}
