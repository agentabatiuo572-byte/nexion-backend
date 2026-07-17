package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;

class TamperConfigAdminAlertConsumerTest {
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final TamperConfigAdminAlertConsumer consumer = new TamperConfigAdminAlertConsumer(deliveryService);

    @Test
    void matchingJ3ConfigEventCreatesADurableSuperadminAlertReceipt() {
        EventOutboxMessage message = message("event-j3-config", "ADMIN_J3_TAMPER_CONFIG_CHANGED");
        when(deliveryService.claim(
                message, TamperConfigAdminAlertConsumer.CONSUMER_GROUP,
                "spring-local-admin-alert", "event-j3-config", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "event-j3-config", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveryService).markSuccess(
                TamperConfigAdminAlertConsumer.CONSUMER_GROUP, "event-j3-config", 1);
    }

    @Test
    void unrelatedEventsAreIgnored() {
        consumer.onOutboxMessage(message("event-other", "OTHER_EVENT"));

        verify(deliveryService, never()).claim(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void incompleteDuplicateIsNotReportedAsDelivered() {
        EventOutboxMessage message = message("event-processing", "ADMIN_J3_TAMPER_CONFIG_CHANGED");
        when(deliveryService.claim(
                message, TamperConfigAdminAlertConsumer.CONSUMER_GROUP,
                "spring-local-admin-alert", "event-processing", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        false, "event-processing", "PROCESSING", 1));

        assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERY_NOT_COMPLETE");

        verify(deliveryService, never()).markSuccess(
                TamperConfigAdminAlertConsumer.CONSUMER_GROUP, "event-processing", 1);
    }

    private EventOutboxMessage message(String eventId, String eventType) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType("TAMPER_ALERT_CONFIG");
        message.setAggregateId("default");
        return message;
    }
}
