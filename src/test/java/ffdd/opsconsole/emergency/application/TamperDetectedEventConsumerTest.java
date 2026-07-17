package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;

class TamperDetectedEventConsumerTest {
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final TamperDetectedEventProjector projector = mock(TamperDetectedEventProjector.class);
    private final TamperDetectedEventConsumer consumer = new TamperDetectedEventConsumer(
            deliveryService, projector);

    @Test
    void canonicalEventIsProjectedOnceAndFedToTheSharedRiskSignalSource() {
        EventOutboxMessage message = event("event-j3-1", """
                {"tamperPath":"free_trial_state","userId":42,"userNo":"U00000042",
                 "attackEffect":"无限领试用","blockedAtEndpoint":"GET /api/trial/eligibility",
                 "isServerAuthoritative":true,"eventCount":1}
                """);
        when(deliveryService.claim(message, TamperDetectedEventConsumer.CONSUMER_GROUP,
                "spring-local-j3-projection", "event-j3-1", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "event-j3-1", "PROCESSING", 1));
        consumer.onOutboxMessage(message);

        verify(projector).project(message, "event-j3-1");
    }

    @Test
    void nonCanonicalOrUnknownPathCannotPolluteJ3Statistics() {
        EventOutboxMessage message = event("event-j3-bad", """
                {"tamperPath":"invented_path","userId":42,"isServerAuthoritative":false,"eventCount":1}
                """);
        when(deliveryService.claim(message, TamperDetectedEventConsumer.CONSUMER_GROUP,
                "spring-local-j3-projection", "event-j3-bad", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "event-j3-bad", "PROCESSING", 1));
        doThrow(new IllegalArgumentException("TAMPER_EVENT_NOT_SERVER_CANONICAL"))
                .when(projector).project(message, "event-j3-bad");

        assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .isInstanceOf(IllegalArgumentException.class);

        verify(deliveryService).markFailure(
                TamperDetectedEventConsumer.CONSUMER_GROUP, "event-j3-bad", 0, "TAMPER_EVENT_NOT_SERVER_CANONICAL");
    }

    private EventOutboxMessage event(String eventId, String payload) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(TamperDetectedEventConsumer.EVENT_TYPE);
        message.setAggregateType("RISK_TAMPER");
        message.setAggregateId("U00000042");
        message.setPayload(payload);
        return message;
    }
}
