package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt proving that D6 quote changes entered the cross-domain event chain. */
@Component
@RequiredArgsConstructor
public class D6FxQuoteChangedEventConsumer {
    static final String CONSUMER_GROUP = "d6-fx-quote-observer";
    static final String TOPIC = "spring-local-d6-fx-quote";
    static final String EVENT_TYPE = "admin.fx_quote_updated";

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPE.equals(message.getEventType())
                || !"FX_QUOTE_CONFIG".equals(message.getAggregateType())
                || !"VND_USDT".equals(message.getAggregateId())) {
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
            throw new IllegalStateException("D6_FX_QUOTE_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}
