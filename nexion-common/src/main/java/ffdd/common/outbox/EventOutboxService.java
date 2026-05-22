package ffdd.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.exception.BizException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnClass(JdbcTemplate.class)
public class EventOutboxService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ERROR_LENGTH = 512;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EventOutboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String publish(String aggregateType, String aggregateId, String eventType, Object payload) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String payloadJson = toJson(payload);
        jdbcTemplate.update("""
                INSERT INTO nx_event_outbox (
                  event_id, aggregate_type, aggregate_id, event_type, payload,
                  status, retry_count, next_retry_at, created_at, updated_at, is_deleted
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', 0, NOW(), NOW(), NOW(), 0)
                """, eventId, aggregateType, aggregateId, eventType, payloadJson);
        return eventId;
    }

    public List<EventOutboxMessage> listPending(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return jdbcTemplate.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload, status,
                       retry_count, next_retry_at, published_at, last_error, created_at, updated_at
                  FROM nx_event_outbox
                 WHERE is_deleted = 0
                   AND status IN ('PENDING', 'FAILED')
                   AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                 ORDER BY id ASC
                 LIMIT ?
                """, this::mapMessage, normalizedLimit);
    }

    public List<EventOutboxMessage> listByAggregate(String aggregateType, String aggregateId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return jdbcTemplate.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload, status,
                       retry_count, next_retry_at, published_at, last_error, created_at, updated_at
                  FROM nx_event_outbox
                 WHERE is_deleted = 0
                   AND aggregate_type = ?
                   AND aggregate_id = ?
                 ORDER BY id DESC
                 LIMIT ?
                """, this::mapMessage, aggregateType, aggregateId, normalizedLimit);
    }

    public boolean markPublished(String eventId) {
        int updated = jdbcTemplate.update("""
                UPDATE nx_event_outbox
                   SET status = 'PUBLISHED', published_at = NOW(), updated_at = NOW(), last_error = NULL
                 WHERE event_id = ?
                   AND is_deleted = 0
                   AND status <> 'PUBLISHED'
                """, eventId);
        return updated > 0;
    }

    public boolean markFailed(String eventId, String errorMessage) {
        String clippedError = clip(errorMessage);
        int updated = jdbcTemplate.update("""
                UPDATE nx_event_outbox
                   SET status = 'FAILED',
                       retry_count = retry_count + 1,
                       next_retry_at = DATE_ADD(NOW(), INTERVAL LEAST(300, POW(2, LEAST(retry_count + 1, 8))) SECOND),
                       last_error = ?,
                       updated_at = NOW()
                 WHERE event_id = ?
                   AND is_deleted = 0
                   AND status <> 'PUBLISHED'
                """, clippedError, eventId);
        return updated > 0;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BizException("Failed to serialize outbox payload");
        }
    }

    private EventOutboxMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setId(rs.getLong("id"));
        message.setEventId(rs.getString("event_id"));
        message.setAggregateType(rs.getString("aggregate_type"));
        message.setAggregateId(rs.getString("aggregate_id"));
        message.setEventType(rs.getString("event_type"));
        message.setPayload(rs.getString("payload"));
        message.setStatus(rs.getString("status"));
        message.setRetryCount(rs.getInt("retry_count"));
        message.setNextRetryAt(toLocalDateTime(rs, "next_retry_at"));
        message.setPublishedAt(toLocalDateTime(rs, "published_at"));
        message.setLastError(rs.getString("last_error"));
        message.setCreatedAt(toLocalDateTime(rs, "created_at"));
        message.setUpdatedAt(toLocalDateTime(rs, "updated_at"));
        return message;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String clip(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Unknown outbox delivery error";
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH ? errorMessage : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }
}
