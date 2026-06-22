package ffdd.opsconsole.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventOutboxService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_DEAD = "DEAD";

    private final EventOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    public String publish(String aggregateType, String aggregateId, String eventType, Object payload) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String payloadJson = toJson(payload);
        mapper.insertEvent(eventId, aggregateType, aggregateId, eventType, payloadJson);
        return eventId;
    }

    public List<EventOutboxMessage> listPending(int limit) {
        return mapper.listPending(normalizeLimit(limit));
    }

    public List<EventOutboxMessage> listPendingByEventType(String eventType, int limit) {
        return mapper.listPendingByEventType(eventType, normalizeLimit(limit));
    }

    public List<EventOutboxMessage> listByAggregate(String aggregateType, String aggregateId, int limit) {
        return mapper.listByAggregate(aggregateType, aggregateId, normalizeLimit(limit));
    }

    public List<EventOutboxMessage> listDead(int limit) {
        return mapper.listByStatus(STATUS_DEAD, normalizeLimit(limit));
    }

    public boolean markPublished(String eventId) {
        return mapper.markPublished(eventId, STATUS_PUBLISHED) > 0;
    }

    public boolean markFailed(String eventId, String errorMessage) {
        String clippedError = clip(errorMessage);
        return mapper.markFailed(eventId, clippedError, properties.maxRetries(), STATUS_DEAD, STATUS_FAILED, STATUS_PENDING) > 0;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BizException("Failed to serialize outbox payload");
        }
    }

    private String clip(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Unknown outbox delivery error";
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH ? errorMessage : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
