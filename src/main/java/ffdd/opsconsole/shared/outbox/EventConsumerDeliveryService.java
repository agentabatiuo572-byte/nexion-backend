package ffdd.opsconsole.shared.outbox;

import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class EventConsumerDeliveryService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_DEAD = "DEAD";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final EventConsumerDeliveryMapper mapper;
    private final EventConsumerDeliveryProperties properties;

    public int maxRetries() {
        return properties.maxRetries();
    }

    @Transactional(rollbackFor = Exception.class)
    public ConsumerClaim claim(
            EventOutboxMessage message,
            String consumerGroup,
            String topic,
            String msgId,
            int rocketmqReconsumeTimes) {
        String eventId = eventId(message, msgId);
        try {
            mapper.insertClaim(
                    eventId,
                    consumerGroup,
                    topic,
                    msgId,
                    valueOrUnknown(message == null ? null : message.getEventType()),
                    message == null ? null : message.getAggregateType(),
                    message == null ? null : message.getAggregateId(),
                    STATUS_PROCESSING,
                    Math.max(0, rocketmqReconsumeTimes));
            return new ConsumerClaim(true, eventId, STATUS_PROCESSING, 1);
        } catch (DuplicateKeyException ignored) {
            // Existing SUCCESS/DEAD rows are idempotency fences; retryable rows are reclaimed below.
        }

        LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(properties.processingTimeoutSeconds());
        int updated = mapper.reclaim(
                eventId,
                consumerGroup,
                topic,
                msgId,
                valueOrUnknown(message == null ? null : message.getEventType()),
                message == null ? null : message.getAggregateType(),
                message == null ? null : message.getAggregateId(),
                STATUS_PROCESSING,
                STATUS_FAILED,
                Math.max(0, rocketmqReconsumeTimes),
                staleBefore);
        EventConsumerDelivery delivery = getByEvent(consumerGroup, eventId);
        int attemptCount = delivery == null || delivery.getAttemptCount() == null ? 0 : delivery.getAttemptCount();
        String status = delivery == null ? "MISSING" : delivery.getStatus();
        return new ConsumerClaim(updated > 0, eventId, status, attemptCount);
    }

    public ConsumerFailure markFailure(
            String consumerGroup,
            String eventId,
            int rocketmqReconsumeTimes,
            String errorMessage) {
        EventConsumerDelivery delivery = getByEvent(consumerGroup, eventId);
        int attemptCount = delivery == null || delivery.getAttemptCount() == null ? 1 : delivery.getAttemptCount();
        boolean dead = attemptCount >= properties.maxRetries() || rocketmqReconsumeTimes + 1 >= properties.maxRetries();
        String status = dead ? STATUS_DEAD : STATUS_FAILED;
        String clippedError = clip(errorMessage);
        mapper.markFailure(consumerGroup, eventId, status, dead, Math.max(0, rocketmqReconsumeTimes), clippedError);
        return new ConsumerFailure(dead, eventId, status, attemptCount);
    }

    public void markSuccess(String consumerGroup, String eventId, int processedCount) {
        mapper.markSuccess(consumerGroup, eventId, STATUS_SUCCESS, Math.max(0, processedCount));
    }

    public void markSkipped(String consumerGroup, String eventId, String reason) {
        mapper.markSkipped(consumerGroup, eventId, STATUS_SKIPPED, clip(reason));
    }

    public List<EventConsumerDelivery> listByStatus(String consumerGroup, String status, int limit) {
        return mapper.listByStatus(blankToNull(consumerGroup), status, normalizeLimit(limit));
    }

    public EventConsumerDelivery getByEvent(String consumerGroup, String eventId) {
        return mapper.getByEvent(consumerGroup, eventId);
    }

    public List<EventConsumerDelivery> listByAggregate(String aggregateType, String aggregateId, int limit) {
        return mapper.listByAggregate(aggregateType, aggregateId, normalizeLimit(limit));
    }

    public List<Map<String, Object>> summary(String consumerGroup) {
        return mapper.summary(blankToNull(consumerGroup));
    }

    private String eventId(EventOutboxMessage message, String msgId) {
        if (message != null && StringUtils.hasText(message.getEventId())) {
            return message.getEventId();
        }
        return StringUtils.hasText(msgId) ? msgId : "UNKNOWN";
    }

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value : "UNKNOWN";
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private String clip(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return "Unknown consumer delivery error";
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH ? errorMessage : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }

    public record ConsumerClaim(boolean claimed, String eventId, String status, int attemptCount) {
    }

    public record ConsumerFailure(boolean dead, String eventId, String status, int attemptCount) {
    }
}
