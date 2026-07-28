package ffdd.opsconsole.finance.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class D1TopupLifecycleEventConsumerTest {
    private final EventConsumerDeliveryService deliveries = Mockito.mock(EventConsumerDeliveryService.class);
    private final D1TopupLifecycleEventConsumer consumer = new D1TopupLifecycleEventConsumer(deliveries);

    @Test
    void acknowledgesCanonicalTopupConfirmation() {
        EventOutboxMessage message = message("d1-event", "wallet.topup_confirmed", "WALLET");
        when(deliveries.claim(message, D1TopupLifecycleEventConsumer.CONSUMER_GROUP,
                D1TopupLifecycleEventConsumer.TOPIC, "d1-event", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "d1-event", "PROCESSING", 1));
        consumer.onOutboxMessage(message);
        verify(deliveries).markSuccess(D1TopupLifecycleEventConsumer.CONSUMER_GROUP, "d1-event", 1);
    }

    @Test
    void ignoresWrongAggregate() {
        consumer.onOutboxMessage(message("d1-invalid", "wallet.topup_confirmed", "ORDER"));
        verifyNoInteractions(deliveries);
    }

    private static EventOutboxMessage message(String id, String type, String aggregate) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(id);
        message.setEventType(type);
        message.setAggregateType(aggregate);
        message.setAggregateId("1");
        message.setPayload("{}");
        return message;
    }
}
