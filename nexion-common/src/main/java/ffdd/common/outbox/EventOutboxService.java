package ffdd.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.exception.BizException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnClass(JdbcTemplate.class)
public class EventOutboxService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_DEAD = "DEAD";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final int maxRetries;

    public EventOutboxService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${nexion.outbox.max-retries:5}") int maxRetries) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.maxRetries = Math.max(1, maxRetries);
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

    public List<EventOutboxMessage> listDead(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return jdbcTemplate.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload, status,
                       retry_count, next_retry_at, published_at, last_error, created_at, updated_at
                  FROM nx_event_outbox
                 WHERE is_deleted = 0
                   AND status = ?
                 ORDER BY updated_at DESC, id DESC
                 LIMIT ?
                """, this::mapMessage, STATUS_DEAD, normalizedLimit);
    }

    public boolean markPublished(String eventId) {
        int updated = jdbcTemplate.update("""
                UPDATE nx_event_outbox
                   SET status = ?, published_at = NOW(), updated_at = NOW(), last_error = NULL
                 WHERE event_id = ?
                   AND is_deleted = 0
                   AND status <> ?
                """, STATUS_PUBLISHED, eventId, STATUS_PUBLISHED);
        return updated > 0;
    }

    public boolean markFailed(String eventId, String errorMessage) {
        String clippedError = clip(errorMessage);
        int updated = jdbcTemplate.update("""
                UPDATE nx_event_outbox
                   SET status = CASE WHEN retry_count + 1 >= ? THEN ? ELSE ? END,
                       next_retry_at = CASE
                         WHEN retry_count + 1 >= ? THEN NULL
                         ELSE DATE_ADD(NOW(), INTERVAL LEAST(300, POW(2, LEAST(retry_count + 1, 8))) SECOND)
                       END,
                       retry_count = retry_count + 1,
                       last_error = ?,
                       updated_at = NOW()
                 WHERE event_id = ?
                   AND is_deleted = 0
                   AND status IN (?, ?)
                """, maxRetries, STATUS_DEAD, STATUS_FAILED, maxRetries,
                clippedError, eventId, STATUS_PENDING, STATUS_FAILED);
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
