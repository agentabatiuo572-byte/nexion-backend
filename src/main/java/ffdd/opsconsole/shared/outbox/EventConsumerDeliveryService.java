package ffdd.opsconsole.shared.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnClass(JdbcTemplate.class)
public class EventConsumerDeliveryService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_DEAD = "DEAD";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final JdbcTemplate jdbcTemplate;
    private final int maxRetries;
    private final int processingTimeoutSeconds;

    public EventConsumerDeliveryService(
            JdbcTemplate jdbcTemplate,
            @Value("${nexion.outbox.rocketmq.consumer.max-retries:5}") int maxRetries,
            @Value("${nexion.outbox.rocketmq.consumer.processing-timeout-seconds:300}") int processingTimeoutSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxRetries = Math.max(1, maxRetries);
        this.processingTimeoutSeconds = Math.max(30, processingTimeoutSeconds);
    }

    public int maxRetries() {
        return maxRetries;
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
            jdbcTemplate.update("""
                    INSERT INTO nx_event_consumer_delivery (
                      event_id, consumer_group, topic, msg_id, event_type, aggregate_type, aggregate_id,
                      status, attempt_count, rocketmq_reconsume_times, first_seen_at, last_seen_at,
                      created_at, updated_at, is_deleted
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, NOW(), NOW(), NOW(), NOW(), 0)
                    """,
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

        LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(processingTimeoutSeconds);
        int updated = jdbcTemplate.update("""
                UPDATE nx_event_consumer_delivery
                   SET status = ?,
                       topic = ?,
                       msg_id = ?,
                       event_type = ?,
                       aggregate_type = ?,
                       aggregate_id = ?,
                       attempt_count = attempt_count + 1,
                       rocketmq_reconsume_times = ?,
                       next_retry_at = NULL,
                       last_error = NULL,
                       last_seen_at = NOW(),
                       updated_at = NOW()
                 WHERE event_id = ?
                   AND consumer_group = ?
                   AND is_deleted = 0
                   AND (
                        status = ?
                        OR (status = ? AND updated_at < ?)
                   )
                """,
                STATUS_PROCESSING,
                topic,
                msgId,
                valueOrUnknown(message == null ? null : message.getEventType()),
                message == null ? null : message.getAggregateType(),
                message == null ? null : message.getAggregateId(),
                Math.max(0, rocketmqReconsumeTimes),
                eventId,
                consumerGroup,
                STATUS_FAILED,
                STATUS_PROCESSING,
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
        boolean dead = attemptCount >= maxRetries || rocketmqReconsumeTimes + 1 >= maxRetries;
        String status = dead ? STATUS_DEAD : STATUS_FAILED;
        String clippedError = clip(errorMessage);
        jdbcTemplate.update("""
                UPDATE nx_event_consumer_delivery
                   SET status = ?,
                       next_retry_at = CASE WHEN ? THEN NULL
                                            ELSE DATE_ADD(NOW(), INTERVAL LEAST(300, POW(2, LEAST(attempt_count, 8))) SECOND)
                                       END,
                       dead_at = CASE WHEN ? THEN NOW() ELSE dead_at END,
                       last_error = ?,
                       rocketmq_reconsume_times = ?,
                       last_seen_at = NOW(),
                       updated_at = NOW()
                 WHERE event_id = ?
                   AND consumer_group = ?
                   AND is_deleted = 0
                """,
                status,
                dead,
                dead,
                clippedError,
                Math.max(0, rocketmqReconsumeTimes),
                eventId,
                consumerGroup);
        return new ConsumerFailure(dead, eventId, status, attemptCount);
    }

    public void markSuccess(String consumerGroup, String eventId, int processedCount) {
        jdbcTemplate.update("""
                UPDATE nx_event_consumer_delivery
                   SET status = ?,
                       processed_at = NOW(),
                       next_retry_at = NULL,
                       dead_at = NULL,
                       created_commissions = ?,
                       last_error = NULL,
                       last_seen_at = NOW(),
                       updated_at = NOW()
                 WHERE event_id = ?
                   AND consumer_group = ?
                   AND is_deleted = 0
                """, STATUS_SUCCESS, Math.max(0, processedCount), eventId, consumerGroup);
    }

    public void markSkipped(String consumerGroup, String eventId, String reason) {
        jdbcTemplate.update("""
                UPDATE nx_event_consumer_delivery
                   SET status = ?,
                       processed_at = NOW(),
                       next_retry_at = NULL,
                       dead_at = NULL,
                       last_error = ?,
                       last_seen_at = NOW(),
                       updated_at = NOW()
                 WHERE event_id = ?
                   AND consumer_group = ?
                   AND is_deleted = 0
                """, STATUS_SKIPPED, clip(reason), eventId, consumerGroup);
    }

    public List<EventConsumerDelivery> listByStatus(String consumerGroup, String status, int limit) {
        int normalizedLimit = normalizeLimit(limit);
        return jdbcTemplate.query("""
                SELECT id, event_id, consumer_group, topic, msg_id, event_type, aggregate_type, aggregate_id,
                       status, attempt_count, rocketmq_reconsume_times, next_retry_at, processed_at, dead_at,
                       created_commissions, last_error, first_seen_at, last_seen_at, created_at, updated_at
                  FROM nx_event_consumer_delivery
                 WHERE is_deleted = 0
                   AND (? IS NULL OR consumer_group = ?)
                   AND status = ?
                 ORDER BY updated_at DESC, id DESC
                 LIMIT ?
                """, this::mapDelivery, blankToNull(consumerGroup), blankToNull(consumerGroup), status, normalizedLimit);
    }

    public EventConsumerDelivery getByEvent(String consumerGroup, String eventId) {
        List<EventConsumerDelivery> deliveries = jdbcTemplate.query("""
                SELECT id, event_id, consumer_group, topic, msg_id, event_type, aggregate_type, aggregate_id,
                       status, attempt_count, rocketmq_reconsume_times, next_retry_at, processed_at, dead_at,
                       created_commissions, last_error, first_seen_at, last_seen_at, created_at, updated_at
                  FROM nx_event_consumer_delivery
                 WHERE is_deleted = 0
                   AND consumer_group = ?
                   AND event_id = ?
                 LIMIT 1
                """, this::mapDelivery, consumerGroup, eventId);
        return deliveries.isEmpty() ? null : deliveries.get(0);
    }

    public List<EventConsumerDelivery> listByAggregate(String aggregateType, String aggregateId, int limit) {
        int normalizedLimit = normalizeLimit(limit);
        return jdbcTemplate.query("""
                SELECT id, event_id, consumer_group, topic, msg_id, event_type, aggregate_type, aggregate_id,
                       status, attempt_count, rocketmq_reconsume_times, next_retry_at, processed_at, dead_at,
                       created_commissions, last_error, first_seen_at, last_seen_at, created_at, updated_at
                  FROM nx_event_consumer_delivery
                 WHERE is_deleted = 0
                   AND aggregate_type = ?
                   AND aggregate_id = ?
                 ORDER BY updated_at DESC, id DESC
                 LIMIT ?
                """, this::mapDelivery, aggregateType, aggregateId, normalizedLimit);
    }

    public List<Map<String, Object>> summary(String consumerGroup) {
        return jdbcTemplate.queryForList("""
                SELECT consumer_group AS consumerGroup,
                       topic,
                       status,
                       COUNT(*) AS total,
                       COALESCE(SUM(attempt_count), 0) AS attempts,
                       MAX(updated_at) AS lastUpdatedAt
                  FROM nx_event_consumer_delivery
                 WHERE is_deleted = 0
                   AND (? IS NULL OR consumer_group = ?)
                 GROUP BY consumer_group, topic, status
                 ORDER BY consumer_group ASC, topic ASC, status ASC
                """, blankToNull(consumerGroup), blankToNull(consumerGroup));
    }

    private EventConsumerDelivery mapDelivery(ResultSet rs, int rowNum) throws SQLException {
        EventConsumerDelivery delivery = new EventConsumerDelivery();
        delivery.setId(rs.getLong("id"));
        delivery.setEventId(rs.getString("event_id"));
        delivery.setConsumerGroup(rs.getString("consumer_group"));
        delivery.setTopic(rs.getString("topic"));
        delivery.setMsgId(rs.getString("msg_id"));
        delivery.setEventType(rs.getString("event_type"));
        delivery.setAggregateType(rs.getString("aggregate_type"));
        delivery.setAggregateId(rs.getString("aggregate_id"));
        delivery.setStatus(rs.getString("status"));
        delivery.setAttemptCount(rs.getInt("attempt_count"));
        delivery.setRocketmqReconsumeTimes(rs.getInt("rocketmq_reconsume_times"));
        delivery.setNextRetryAt(toLocalDateTime(rs, "next_retry_at"));
        delivery.setProcessedAt(toLocalDateTime(rs, "processed_at"));
        delivery.setDeadAt(toLocalDateTime(rs, "dead_at"));
        delivery.setCreatedCommissions(rs.getInt("created_commissions"));
        delivery.setLastError(rs.getString("last_error"));
        delivery.setFirstSeenAt(toLocalDateTime(rs, "first_seen_at"));
        delivery.setLastSeenAt(toLocalDateTime(rs, "last_seen_at"));
        delivery.setCreatedAt(toLocalDateTime(rs, "created_at"));
        delivery.setUpdatedAt(toLocalDateTime(rs, "updated_at"));
        return delivery;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
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
