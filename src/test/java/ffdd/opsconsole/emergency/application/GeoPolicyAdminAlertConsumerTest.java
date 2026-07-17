package ffdd.opsconsole.emergency.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;

class GeoPolicyAdminAlertConsumerTest {
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final GeoPolicyAdminAlertConsumer consumer = new GeoPolicyAdminAlertConsumer(deliveryService);

    @Test
    void matchingJ2EventCreatesADurableSuperadminAlertReceipt() {
        EventOutboxMessage message = message("event-j2", "ADMIN_KILLSWITCH_TOGGLED", "geo-block");
        when(deliveryService.claim(
                message, GeoPolicyAdminAlertConsumer.CONSUMER_GROUP,
                "spring-local-admin-alert", "event-j2", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "event-j2", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveryService).markSuccess(GeoPolicyAdminAlertConsumer.CONSUMER_GROUP, "event-j2", 1);
    }

    @Test
    void unrelatedOutboxEventsAreIgnored() {
        EventOutboxMessage message = message("event-other", "OTHER_EVENT", "geo-block");

        consumer.onOutboxMessage(message);

        verify(deliveryService, never()).claim(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void incompleteDuplicateCannotBeFalselyAcknowledgedByTheOutboxScheduler() {
        EventOutboxMessage message = message("event-processing", "ADMIN_KILLSWITCH_TOGGLED", "geo-block");
        when(deliveryService.claim(
                message, GeoPolicyAdminAlertConsumer.CONSUMER_GROUP,
                "spring-local-admin-alert", "event-processing", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "event-processing", "PROCESSING", 1));

        assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERY_NOT_COMPLETE");

        verify(deliveryService, never()).markSuccess(
                GeoPolicyAdminAlertConsumer.CONSUMER_GROUP, "event-processing", 1);
    }

    private EventOutboxMessage message(String eventId, String eventType, String aggregateId) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType("KILL_SWITCH");
        message.setAggregateId(aggregateId);
        return message;
    }
}
