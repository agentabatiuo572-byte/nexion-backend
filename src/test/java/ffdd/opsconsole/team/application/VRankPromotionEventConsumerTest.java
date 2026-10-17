package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import org.junit.jupiter.api.Test;

class VRankPromotionEventConsumerTest {
    final TeamCommissionRepository repository = mock(TeamCommissionRepository.class);
    final VRankPromotionEngine engine = mock(VRankPromotionEngine.class);
    final EventConsumerDeliveryService delivery = mock(EventConsumerDeliveryService.class);
    final VRankPromotionEventConsumer consumer = new VRankPromotionEventConsumer(
            new ObjectMapper(), repository, engine, delivery);

    EventOutboxMessage message(String id) {
        var message = new EventOutboxMessage();
        message.setEventId(id);
        message.setEventType("VRANK_PROMOTION_COMPLETED");
        message.setPayload("{\"userId\":200,\"fromCode\":\"V0\",\"toCode\":\"V1\"}");
        return message;
    }

    void claim(boolean claimed, String status) {
        when(delivery.claim(any(), anyString(), anyString(), anyString(), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(claimed, "event-1", status, 1));
    }

    @Test void completedDeliveryNeverReevaluatesSponsor() {
        claim(false, "SUCCESS");
        consumer.onPromotionCompleted(message("event-1"));
        verifyNoInteractions(repository, engine);
        verify(delivery, never()).markSuccess(anyString(), anyString(), anyInt());
    }

    @Test void originalEventIdReachesEngineAndCompletionFollowsEvaluation() {
        claim(true, "PROCESSING");
        when(repository.findSponsorUserId(200L)).thenReturn(100L);
        consumer.onPromotionCompleted(message("event-1"));
        var ordered = inOrder(delivery, engine);
        ordered.verify(delivery).claim(any(), eq(VRankPromotionEventConsumer.CONSUMER_GROUP),
                eq("spring-outbox"), eq("event-1"), eq(0));
        ordered.verify(engine).evaluate(new VRankPromotionContext(100L,
                VRankPromotionContext.TriggerType.SYSTEM_EVALUATION, "event-1", "ENGINE"));
        ordered.verify(delivery).markSuccess(VRankPromotionEventConsumer.CONSUMER_GROUP, "event-1", 0);
    }

    @Test void incompleteClaimMustRemainRetryable() {
        for (String status : new String[]{"PROCESSING", "FAILED", "DEAD", "MISSING"}) {
            claim(false, status);
            assertThatThrownBy(() -> consumer.onPromotionCompleted(message("event-1")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(status);
        }
        verifyNoInteractions(repository, engine);
    }

    @Test void failedEvaluationDoesNotPersistCompletion() {
        claim(true, "PROCESSING");
        when(repository.findSponsorUserId(200L)).thenReturn(100L);
        when(engine.evaluate(any())).thenThrow(new IllegalStateException("reward failed"));
        assertThatThrownBy(() -> consumer.onPromotionCompleted(message("event-1")))
                .hasMessage("reward failed");
        verify(delivery, never()).markSuccess(anyString(), anyString(), anyInt());
        verify(delivery, never()).markFailure(anyString(), anyString(), anyInt(), anyString());
    }

    @Test void noSponsorCompletesWithoutPromotion() {
        claim(true, "PROCESSING");
        when(repository.findSponsorUserId(200L)).thenReturn(null);
        consumer.onPromotionCompleted(message("event-1"));
        verifyNoInteractions(engine);
        verify(delivery).markSuccess(VRankPromotionEventConsumer.CONSUMER_GROUP, "event-1", 0);
    }

    @Test void missingEventIdentityCannotPromote() {
        assertThatThrownBy(() -> consumer.onPromotionCompleted(message(" ")))
                .hasMessage("VRANK_PROMOTION_EVENT_ID_REQUIRED");
        verifyNoInteractions(delivery, repository, engine);
    }
}
