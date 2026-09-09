package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestCanonicalEventConsumerTest {
    private final QuestCanonicalEventBindingMapper bindingMapper = mock(QuestCanonicalEventBindingMapper.class);
    private final DayOneInstanceMapper dayOneInstances = mock(DayOneInstanceMapper.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final QuestCanonicalEventProjector projector = mock(QuestCanonicalEventProjector.class);
    private final QuestCanonicalEventConsumer consumer = new QuestCanonicalEventConsumer(
            bindingMapper, dayOneInstances, deliveryService, projector, new ObjectMapper());

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

    @Test
    void thresholdFactWithoutBindingBecomesDurableWaitWithoutProjectionOrRetry() {
        EventOutboxMessage message = event("evt-wait", "H3_DAY_ONE_EARN_PAGE_VIEWED");
        when(bindingMapper.countActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(0);
        when(deliveryService.claimPendingBinding(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-wait", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "evt-wait", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveryService).claimPendingBinding(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-wait", 0);
        verify(projector, never()).project(message, "evt-wait");
        verify(deliveryService, never()).markFailure(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-wait", 0, "H3_BINDING_UNAVAILABLE");
    }

    @Test
    void lateBindingResumesTheSameWaitingDeliveryOnlyOnce() {
        EventOutboxMessage message = event("evt-late", "H3_DAY_ONE_EARN_PAGE_VIEWED");
        when(bindingMapper.countActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(1);
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-late", 0))
                .thenReturn(
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt-late", "PENDING_BINDING", 1),
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt-late", "SUCCESS", 1));
        when(deliveryService.resumePendingBinding(QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-late"))
                .thenReturn(true);

        consumer.onOutboxMessage(message);
        consumer.onOutboxMessage(message);

        verify(deliveryService).resumePendingBinding(QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-late");
        verify(projector, org.mockito.Mockito.times(1)).project(message, "evt-late");
    }

    @Test
    void frozenDayOneSnapshotClaimsAnEventAfterItsCurrentPcBindingWasRemoved() {
        EventOutboxMessage message = event("evt-day-one-frozen", "H3_DAY_ONE_EARN_PAGE_VIEWED");
        message.setEventTs(LocalDateTime.of(2026, 9, 9, 10, 0));
        message.setPayload("{\"user_id\":42}");
        when(bindingMapper.countActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(0);
        when(dayOneInstances.listInWindowSnapshotBindings(List.of(42L), "H3_DAY_ONE_EARN_PAGE_VIEWED", message.getEventTs()))
                .thenReturn(List.of(new DayOneSnapshotBinding(71L, 42L, "DAY_ONE:20260909T090000", 9L,
                        "visit_earn", "DAY_ONE_EARN", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED",
                        "user_id", "{}")));
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-day-one-frozen", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "evt-day-one-frozen", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(projector).project(message, "evt-day-one-frozen");
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
