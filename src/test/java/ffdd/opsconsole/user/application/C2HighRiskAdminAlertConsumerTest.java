package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;

class C2HighRiskAdminAlertConsumerTest {
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final C2HighRiskAdminAlertConsumer consumer = new C2HighRiskAdminAlertConsumer(deliveryService);

    @Test
    void matchingC2EventCreatesDurableHighRiskAlertReceipt() {
        EventOutboxMessage message = message("event-c2", "admin.user_frozen", "USER");
        when(deliveryService.claim(message, C2HighRiskAdminAlertConsumer.CONSUMER_GROUP,
                C2HighRiskAdminAlertConsumer.TOPIC, "event-c2", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "event-c2", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveryService).markSuccess(C2HighRiskAdminAlertConsumer.CONSUMER_GROUP, "event-c2", 1);
    }

    @Test
    void unrelatedOrWrongAggregateEventsAreIgnored() {
        consumer.onOutboxMessage(message("other", "OTHER_EVENT", "USER"));
        consumer.onOutboxMessage(message("wrong-aggregate", "admin.user_frozen", "ORDER"));

        verify(deliveryService, never()).claim(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void incompleteDuplicateCannotBeFalselyAcknowledged() {
        EventOutboxMessage message = message("event-processing", "admin.user_impersonation_ended", "USER_IMPERSONATION");
        when(deliveryService.claim(message, C2HighRiskAdminAlertConsumer.CONSUMER_GROUP,
                C2HighRiskAdminAlertConsumer.TOPIC, "event-processing", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "event-processing", "PROCESSING", 1));

        assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERY_NOT_COMPLETE");
    }

    private EventOutboxMessage message(String eventId, String eventType, String aggregateType) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType(aggregateType);
        message.setAggregateId("target-1");
        return message;
    }
}
