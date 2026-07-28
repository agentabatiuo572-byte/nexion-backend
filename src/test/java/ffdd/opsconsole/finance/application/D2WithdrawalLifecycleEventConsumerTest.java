package ffdd.opsconsole.finance.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class D2WithdrawalLifecycleEventConsumerTest {
    private final EventConsumerDeliveryService deliveries = Mockito.mock(EventConsumerDeliveryService.class);
    private final D2WithdrawalLifecycleEventConsumer consumer = new D2WithdrawalLifecycleEventConsumer(deliveries);

    @Test
    void acknowledgesCanonicalSubmittedEvent() {
        EventOutboxMessage message = message("d2-event", "withdraw.submitted", "WITHDRAWAL");
        when(deliveries.claim(message, D2WithdrawalLifecycleEventConsumer.CONSUMER_GROUP,
                D2WithdrawalLifecycleEventConsumer.TOPIC, "d2-event", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "d2-event", "PROCESSING", 1));
        consumer.onOutboxMessage(message);
        verify(deliveries).markSuccess(D2WithdrawalLifecycleEventConsumer.CONSUMER_GROUP, "d2-event", 1);
    }

    @Test
    void ignoresWrongAggregate() {
        consumer.onOutboxMessage(message("d2-invalid", "withdraw.submitted", "USER"));
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
