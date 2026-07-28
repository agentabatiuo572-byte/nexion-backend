package ffdd.opsconsole.treasury.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class D3TreasuryLifecycleEventConsumerTest {
    private final EventConsumerDeliveryService deliveries = Mockito.mock(EventConsumerDeliveryService.class);
    private final D3TreasuryLifecycleEventConsumer consumer = new D3TreasuryLifecycleEventConsumer(deliveries);

    @Test
    void acknowledgesCanonicalReserveInjectionEvent() {
        EventOutboxMessage message = message(
                "d3-event", "admin.treasury_reserve_injected", "TREASURY_RESERVE");
        when(deliveries.claim(message, D3TreasuryLifecycleEventConsumer.CONSUMER_GROUP,
                D3TreasuryLifecycleEventConsumer.TOPIC, "d3-event", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "d3-event", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveries).markSuccess(D3TreasuryLifecycleEventConsumer.CONSUMER_GROUP, "d3-event", 1);
    }

    @Test
    void ignoresWrongAggregate() {
        consumer.onOutboxMessage(message(
                "d3-invalid", "admin.treasury_reserve_injected", "USER"));
        verifyNoInteractions(deliveries);
    }

    private static EventOutboxMessage message(String id, String type, String aggregate) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(id);
        message.setEventType(type);
        message.setAggregateType(aggregate);
        message.setAggregateId("D3");
        message.setPayload("{}");
        return message;
    }
}
