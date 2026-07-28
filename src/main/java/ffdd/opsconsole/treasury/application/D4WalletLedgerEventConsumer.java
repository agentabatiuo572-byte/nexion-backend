package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt for every canonical immutable D4 wallet-ledger entry. */
@Component
@RequiredArgsConstructor
public class D4WalletLedgerEventConsumer {
    static final String CONSUMER_GROUP = "d4-wallet-ledger-observer";
    static final String TOPIC = "spring-local-d4-wallet-ledger";
    static final String EVENT_TYPE = "wallet.ledger_posted";

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPE.equals(message.getEventType())
                || !"WALLET_LEDGER".equals(message.getAggregateType())) {
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
            throw new IllegalStateException("D4_WALLET_LEDGER_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}
