package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.H3WeeklyParticipationMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Consumes the already server-verified production task completion and turns it
 * into a separate H3 threshold fact; it is never a direct quest binding.
 */
@Component
@RequiredArgsConstructor
public class H3WeeklyParticipationEventConsumer {
    static final String CONSUMER_GROUP = "h3-weekly-participation-evaluator";
    static final String TOPIC = "spring-local-h3-weekly-participation";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final H3WeeklyParticipationMapper mapper;
    private final H3WeeklyParticipationEvaluator evaluator;
    private final EventConsumerDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (!isEligibleSource(message)) return;
        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
        if (claim.claimed()) {
            try {
                Map<String, Object> payload = objectMapper.readValue(message.getPayload(), MAP_TYPE);
                Long userId = positiveLong(payload.get("user_id"));
                if (userId == null) throw new IllegalArgumentException("H3_COMPUTE_COMPLETION_USER_REQUIRED");
                String taskNo = message.getAggregateId().trim();
                H3WeeklyParticipationMapper.VerifiedProductionCompletion completion =
                        mapper.verifiedProductionCompletedTask(userId, taskNo);
                if (completion == null || completion.completedAt() == null) {
                    throw new IllegalArgumentException("H3_COMPUTE_COMPLETION_NOT_VERIFIED");
                }
                java.time.LocalDateTime rolloutEffectiveAt = mapper.rolloutEffectiveAt();
                if (rolloutEffectiveAt == null) {
                    throw new IllegalStateException("H3_WEEKLY_PARTICIPATION_ROLLOUT_UNAVAILABLE");
                }
                if (!completion.completedAt().isAfter(rolloutEffectiveAt)
                        || !H3WeeklyParticipationEvaluator.weeklyInstance(
                                completion.completedAt().atZone(H3WeeklyParticipationEvaluator.WEEK_ZONE).toInstant())
                                .equals(evaluator.currentWeeklyInstance())) {
                    deliveryService.markSkipped(CONSUMER_GROUP, claim.eventId(), "H3_COMPUTE_COMPLETION_OUTSIDE_CURRENT_ROLLOUT_WEEK");
                    return;
                }
                evaluator.recordVerifiedProductionComputeCompletion(userId, taskNo, completion.completedAt());
                deliveryService.markSuccess(CONSUMER_GROUP, claim.eventId(), 1);
            } catch (RuntimeException ex) {
                deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
                throw ex;
            } catch (Exception ex) {
                deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, "H3_COMPUTE_COMPLETION_PAYLOAD_INVALID");
                throw new IllegalArgumentException("H3_COMPUTE_COMPLETION_PAYLOAD_INVALID", ex);
            }
            return;
        }
        if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
            throw new IllegalStateException("H3_COMPUTE_COMPLETION_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }

    private boolean isEligibleSource(EventOutboxMessage message) {
        return message != null
                && "task.completed".equals(message.getEventType())
                && "COMPUTE_TASK".equals(message.getAggregateType())
                && StringUtils.hasText(message.getAggregateId())
                && StringUtils.hasText(message.getEventId())
                && Boolean.TRUE.equals(message.getServerAuthoritative());
    }

    private Long positiveLong(Object value) {
        try {
            long parsed = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(value == null ? "" : String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
