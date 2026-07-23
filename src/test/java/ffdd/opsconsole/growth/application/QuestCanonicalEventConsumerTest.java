package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;

class QuestCanonicalEventConsumerTest {
    private final QuestCanonicalEventBindingMapper bindingMapper = mock(QuestCanonicalEventBindingMapper.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final QuestCanonicalEventProjector projector = mock(QuestCanonicalEventProjector.class);
    private final QuestCanonicalEventConsumer consumer = new QuestCanonicalEventConsumer(
            bindingMapper, deliveryService, projector);

    @Test
    void configuredCanonicalEventUsesDurableClaimBeforeProjection() {
        EventOutboxMessage message = event("evt-order-1", "checkout.started");
        when(bindingMapper.countActiveBindings("checkout.started")).thenReturn(1);
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-order-1", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "evt-order-1", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(projector).project(message, "evt-order-1");
    }

    @Test
    void unconfiguredEventsAreIgnoredWithoutCreatingFalseDeliveryReceipts() {
        EventOutboxMessage message = event("evt-unknown", "unknown.event");
        when(bindingMapper.countActiveBindings("unknown.event")).thenReturn(0);

        consumer.onOutboxMessage(message);

        verify(deliveryService, never()).claim(
                message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-unknown", 0);
        verify(projector, never()).project(message, "evt-unknown");
    }

    @Test
    void failedProjectionIsDurablyMarkedForRetry() {
        EventOutboxMessage message = event("evt-bad", "LEARNING_COURSE_COMPLETED");
        when(bindingMapper.countActiveBindings("LEARNING_COURSE_COMPLETED")).thenReturn(1);
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-bad", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "evt-bad", "PROCESSING", 1));
        doThrow(new IllegalArgumentException("QUEST_CANONICAL_USER_ID_REQUIRED"))
                .when(projector).project(message, "evt-bad");

        assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QUEST_CANONICAL_USER_ID_REQUIRED");
        verify(deliveryService).markFailure(
                QuestCanonicalEventConsumer.CONSUMER_GROUP,
                "evt-bad", 0, "QUEST_CANONICAL_USER_ID_REQUIRED");
    }

    private EventOutboxMessage event(String eventId, String eventType) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType("TEST");
        message.setAggregateId(eventId);
        message.setPayload("{}");
        return message;
    }
}
