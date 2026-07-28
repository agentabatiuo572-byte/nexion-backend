package ffdd.opsconsole.treasury.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class D4WalletLedgerEventConsumerTest {
    private final EventConsumerDeliveryService deliveries = Mockito.mock(EventConsumerDeliveryService.class);
    private final D4WalletLedgerEventConsumer consumer = new D4WalletLedgerEventConsumer(deliveries);

    @Test
    void acknowledgesCanonicalWalletLedgerEvent() {
        EventOutboxMessage message = message("d4-event", "WALLET_LEDGER");
        when(deliveries.claim(message, D4WalletLedgerEventConsumer.CONSUMER_GROUP,
                D4WalletLedgerEventConsumer.TOPIC, "d4-event", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "d4-event", "PROCESSING", 1));
        consumer.onOutboxMessage(message);
        verify(deliveries).markSuccess(D4WalletLedgerEventConsumer.CONSUMER_GROUP, "d4-event", 1);
    }

    @Test
    void ignoresWrongAggregate() {
        consumer.onOutboxMessage(message("wrong", "USER"));
        verifyNoInteractions(deliveries);
    }

    private static EventOutboxMessage message(String id, String aggregate) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(id);
        message.setEventType("wallet.ledger_posted");
        message.setAggregateType(aggregate);
        message.setAggregateId("D4-BIZ");
        message.setPayload("{}");
        return message;
    }
}
