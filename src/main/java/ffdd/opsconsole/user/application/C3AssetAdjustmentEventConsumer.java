package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt for canonical C3 balance and wallet-ledger events. */
@Component
@RequiredArgsConstructor
public class C3AssetAdjustmentEventConsumer {
    static final String CONSUMER_GROUP = "c3-asset-adjustment-observer";
    static final String TOPIC = "spring-local-c3-asset-adjustment";
    private static final Map<String, String> EVENT_AGGREGATES = Map.of(
            "admin.balance_adjusted", "USER_ASSET_ADJUSTMENT",
            "admin.bill_adjusted", "WALLET_LEDGER");

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_AGGREGATES.containsKey(message.getEventType())
                || !EVENT_AGGREGATES.get(message.getEventType()).equals(message.getAggregateType())) {
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
            throw new IllegalStateException("C3_ASSET_ADJUSTMENT_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}
