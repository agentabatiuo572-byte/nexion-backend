package ffdd.opsconsole.user.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class C3AssetAdjustmentEventConsumerTest {
    private final EventConsumerDeliveryService deliveries = Mockito.mock(EventConsumerDeliveryService.class);
    private final C3AssetAdjustmentEventConsumer consumer = new C3AssetAdjustmentEventConsumer(deliveries);

    @Test
    void acknowledgesCanonicalBalanceAdjustment() {
        EventOutboxMessage message = message("event-c3-balance", "admin.balance_adjusted", "USER_ASSET_ADJUSTMENT");
        when(deliveries.claim(message, C3AssetAdjustmentEventConsumer.CONSUMER_GROUP,
                C3AssetAdjustmentEventConsumer.TOPIC, "event-c3-balance", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "event-c3-balance", "PROCESSING", 1));
        consumer.onOutboxMessage(message);
        verify(deliveries).markSuccess(C3AssetAdjustmentEventConsumer.CONSUMER_GROUP, "event-c3-balance", 1);
    }

    @Test
    void ignoresMismatchedAggregateContract() {
        consumer.onOutboxMessage(message("event-c3-invalid", "admin.bill_adjusted", "USER_ASSET_ADJUSTMENT"));
        verifyNoInteractions(deliveries);
    }

    private static EventOutboxMessage message(String eventId, String eventType, String aggregateType) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType(aggregateType);
        message.setAggregateId("1");
        message.setPayload("{}");
        return message;
    }
}
