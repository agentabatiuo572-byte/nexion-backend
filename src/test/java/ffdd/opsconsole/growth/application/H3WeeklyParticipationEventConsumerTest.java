package ffdd.opsconsole.growth.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.H3WeeklyParticipationMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class H3WeeklyParticipationEventConsumerTest {
    private final H3WeeklyParticipationMapper mapper = org.mockito.Mockito.mock(H3WeeklyParticipationMapper.class);
    private final H3WeeklyParticipationEvaluator evaluator = org.mockito.Mockito.mock(H3WeeklyParticipationEvaluator.class);
    private final EventConsumerDeliveryService deliveries = org.mockito.Mockito.mock(EventConsumerDeliveryService.class);
    private final H3WeeklyParticipationEventConsumer consumer =
            new H3WeeklyParticipationEventConsumer(mapper, evaluator, deliveries, new ObjectMapper());

    @Test
    void verifiedProductionTaskCompletedEventEntersTheThresholdEvaluator() {
        EventOutboxMessage message = productionTaskCompleted("EVENT-1", "CTA-9", 42L);
        when(deliveries.claim(message, H3WeeklyParticipationEventConsumer.CONSUMER_GROUP,
                H3WeeklyParticipationEventConsumer.TOPIC, "EVENT-1", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVENT-1", "PROCESSING", 1));
        when(mapper.verifiedProductionCompletedTask(42L, "CTA-9"))
                .thenReturn(new H3WeeklyParticipationMapper.VerifiedProductionCompletion(LocalDateTime.of(2026, 9, 7, 8, 0)));
        when(mapper.rolloutEffectiveAt()).thenReturn(LocalDateTime.of(2026, 9, 7, 7, 0));
        when(evaluator.currentWeeklyInstance()).thenReturn("WEEK:2026-W37");

        consumer.onOutboxMessage(message);

        verify(evaluator).recordVerifiedProductionComputeCompletion(42L, "CTA-9", LocalDateTime.of(2026, 9, 7, 8, 0));
        verify(deliveries).markSuccess(H3WeeklyParticipationEventConsumer.CONSUMER_GROUP, "EVENT-1", 1);
    }

    @Test
    void rawOrUnverifiedTaskCompletedEventCannotEnterTheEvaluator() {
        EventOutboxMessage message = productionTaskCompleted("EVENT-2", "CTA-9", 42L);
        message.setServerAuthoritative(false);

        consumer.onOutboxMessage(message);

        verify(evaluator, never()).recordVerifiedProductionComputeCompletion(42L, "CTA-9", LocalDateTime.of(2026, 9, 7, 8, 0));
        verify(deliveries, never()).claim(message, H3WeeklyParticipationEventConsumer.CONSUMER_GROUP,
                H3WeeklyParticipationEventConsumer.TOPIC, "EVENT-2", 0);
    }

    @Test
    void aTaskFromAnotherAccountCannotBeCounted() {
        EventOutboxMessage message = productionTaskCompleted("EVENT-3", "CTA-9", 43L);
        when(deliveries.claim(message, H3WeeklyParticipationEventConsumer.CONSUMER_GROUP,
                H3WeeklyParticipationEventConsumer.TOPIC, "EVENT-3", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVENT-3", "PROCESSING", 1));
        when(mapper.verifiedProductionCompletedTask(43L, "CTA-9")).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .hasMessage("H3_COMPUTE_COMPLETION_NOT_VERIFIED");

        verify(evaluator, never()).recordVerifiedProductionComputeCompletion(43L, "CTA-9", LocalDateTime.of(2026, 9, 7, 8, 0));
        verify(deliveries).markFailure(H3WeeklyParticipationEventConsumer.CONSUMER_GROUP, "EVENT-3", 0,
                "H3_COMPUTE_COMPLETION_NOT_VERIFIED");
    }

    @Test
    void completedBeforeRolloutIsSkippedInsteadOfBackfilled() {
        EventOutboxMessage message = productionTaskCompleted("EVENT-OLD", "CTA-OLD", 42L);
        when(deliveries.claim(message, H3WeeklyParticipationEventConsumer.CONSUMER_GROUP,
                H3WeeklyParticipationEventConsumer.TOPIC, "EVENT-OLD", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVENT-OLD", "PROCESSING", 1));
        when(mapper.verifiedProductionCompletedTask(42L, "CTA-OLD"))
                .thenReturn(new H3WeeklyParticipationMapper.VerifiedProductionCompletion(LocalDateTime.of(2026, 9, 6, 8, 0)));
        when(mapper.rolloutEffectiveAt()).thenReturn(LocalDateTime.of(2026, 9, 7, 7, 0));

        consumer.onOutboxMessage(message);

        verify(evaluator, never()).recordVerifiedProductionComputeCompletion(42L, "CTA-OLD", LocalDateTime.of(2026, 9, 6, 8, 0));
        verify(deliveries).markSkipped(H3WeeklyParticipationEventConsumer.CONSUMER_GROUP, "EVENT-OLD",
                "H3_COMPUTE_COMPLETION_OUTSIDE_CURRENT_ROLLOUT_WEEK");
    }

    @Test
    void completionFromThePreviousWeekIsSkippedEvenWhenItArrivesAfterRollout() {
        EventOutboxMessage message = productionTaskCompleted("EVENT-PREVIOUS-WEEK", "CTA-OLD-WEEK", 42L);
        when(deliveries.claim(message, H3WeeklyParticipationEventConsumer.CONSUMER_GROUP,
                H3WeeklyParticipationEventConsumer.TOPIC, "EVENT-PREVIOUS-WEEK", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVENT-PREVIOUS-WEEK", "PROCESSING", 1));
        when(mapper.verifiedProductionCompletedTask(42L, "CTA-OLD-WEEK"))
                .thenReturn(new H3WeeklyParticipationMapper.VerifiedProductionCompletion(LocalDateTime.of(2026, 9, 6, 20, 0)));
        when(mapper.rolloutEffectiveAt()).thenReturn(LocalDateTime.of(2026, 9, 6, 16, 0));
        when(evaluator.currentWeeklyInstance()).thenReturn("WEEK:2026-W37");

        consumer.onOutboxMessage(message);

        verify(evaluator, never()).recordVerifiedProductionComputeCompletion(42L, "CTA-OLD-WEEK", LocalDateTime.of(2026, 9, 6, 20, 0));
        verify(deliveries).markSkipped(H3WeeklyParticipationEventConsumer.CONSUMER_GROUP, "EVENT-PREVIOUS-WEEK",
                "H3_COMPUTE_COMPLETION_OUTSIDE_CURRENT_ROLLOUT_WEEK");
    }
    private static EventOutboxMessage productionTaskCompleted(String eventId, String taskNo, long userId) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType("task.completed");
        message.setAggregateType("COMPUTE_TASK");
        message.setAggregateId(taskNo);
        message.setServerAuthoritative(true);
        message.setEventTs(LocalDateTime.of(2026, 9, 7, 9, 0));
        message.setPayload("{\"user_id\":" + userId + "}");
        return message;
    }
}