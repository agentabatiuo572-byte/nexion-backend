package ffdd.opsconsole.shared.outbox;

import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Coordinates both schedulers across JVMs while preserving each listener's transaction boundary. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventOutboxDispatcher {
    private static final String EVENT_LOCK = EventOutboxMapper.DISPATCH_LOCK_NAMESPACE;
    private static final String LANE_LOCK = "nx:obl:";
    private static final String LOCK_NAME = "CONCAT(?, LEFT(SHA2(DATABASE(), 256), 32), ':', ?)";
    private static final String ACQUIRE_LOCK = "SELECT CASE GET_LOCK(" + LOCK_NAME
            + ", 0) WHEN 1 THEN CONNECTION_ID() WHEN 0 THEN 0 END";
    private static final String RELEASE_LOCK = "SELECT RELEASE_LOCK(" + LOCK_NAME + ")";

    private final HikariDataSource dataSource;
    private final EventOutboxMapper mapper;
    private final EventOutboxService outboxService;
    private final EventConsumerDeliveryProperties deliveryProperties;
    private final ApplicationEventPublisher eventPublisher;

    public Result dispatch(EventOutboxMessage message, String exactEventType) {
        // Keep the advisory lock on a dedicated session, without an open transaction.
        try (Connection connection = dataSource.getConnection()) {
            try {
                return dispatch(connection, message, exactEventType);
            } catch (SQLException ex) {
                // A pooled connection must never be reused with uncertain lock ownership.
                dataSource.evictConnection(connection);
                throw ex;
            }
        } catch (SQLException | RuntimeException ex) {
            log.error("Outbox dispatch coordination failed eventId={} eventType={}",
                    message.getEventId(), message.getEventType(), ex);
            return Result.FAILED;
        }
    }

    void dispatchComputeLane(String eventType, int laneCount, int lane) {
        String laneKey = eventType + ":" + lane;
        try (Connection connection = dataSource.getConnection()) {
            try {
                if (executeLockCommand(connection, ACQUIRE_LOCK, LANE_LOCK, laneKey) == 0) return;
                try {
                    var newest = new ArrayList<>(mapper.listPendingByCanonicalLane(eventType, laneCount, lane, true, 25));
                    Collections.reverse(newest);
                    for (EventOutboxMessage message : newest) dispatch(connection, message, null);
                    for (EventOutboxMessage message : mapper.listPendingByCanonicalLane(eventType, laneCount, lane, false, 15)) {
                        dispatch(connection, message, null);
                    }
                } finally {
                    releaseLock(connection, LANE_LOCK, laneKey);
                }
            } catch (SQLException ex) {
                dataSource.evictConnection(connection);
                throw ex;
            }
        } catch (SQLException | RuntimeException ex) {
            log.error("Compute outbox lane failed eventType={} lane={}", eventType, lane, ex);
        }
    }

    private Result dispatch(Connection connection, EventOutboxMessage message, String exactEventType) throws SQLException {
        String key = Long.toString(message.getId());
        long ownerId = executeLockCommand(connection, ACQUIRE_LOCK, EVENT_LOCK, key);
        if (ownerId == 0) return Result.SKIPPED;
        try {
            return dispatchOwned(message, exactEventType, ownerId);
        } catch (RuntimeException ex) {
            log.error("Outbox dispatch coordination failed eventId={} eventType={}",
                    message.getEventId(), message.getEventType(), ex);
            return Result.FAILED;
        } finally {
            releaseLock(connection, EVENT_LOCK, key);
        }
    }

    private Result dispatchOwned(EventOutboxMessage message, String exactEventType, long ownerId) {
        var state = mapper.findDispatchState(message.getId(), deliveryProperties.processingTimeoutSeconds(), ownerId);
        if (state == null) {
            return Result.SKIPPED;
        }
        if (state.activeClaimUntil() != null) {
            // A previous process can leave a committed consumer claim before it exits.
            // Let that claim expire before replaying without spending a delivery attempt.
            mapper.deferDispatchUntil(message.getId(), state.activeClaimUntil(), ownerId);
            return Result.SKIPPED;
        }

        try {
            if (exactEventType != null && !exactEventType.equals(message.getEventType())) {
                throw new IllegalStateException("C1_AUDIT_ENVELOPE_INVALID");
            }
            outboxService.assertDispatchAllowed(message);
            eventPublisher.publishEvent(message);
        } catch (RuntimeException ex) {
            log.warn("Outbox delivery failed eventId={} eventType={} error={}",
                    message.getEventId(), message.getEventType(), ex.getMessage());
            outboxService.markFailed(message.getId(), ex.getMessage(), ownerId);
            return Result.FAILED;
        }

        if (!outboxService.markPublished(message.getId(), ownerId)) {
            log.warn("Outbox message was delivered but status was not updated eventId={}", message.getEventId());
            return Result.SKIPPED;
        }
        return Result.PUBLISHED;
    }

    private void releaseLock(Connection connection, String namespace, String key) throws SQLException {
        if (executeLockCommand(connection, RELEASE_LOCK, namespace, key) != 1) {
            throw new SQLException("Outbox dispatch lock was lost");
        }
    }

    private long executeLockCommand(Connection connection, String sql, String namespace, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    long result = rows.getLong(1);
                    if (!rows.wasNull()) {
                        return result;
                    }
                }
                throw new SQLException("MySQL could not determine outbox dispatch lock ownership");
            }
        }
    }

    public enum Result {
        SKIPPED, PUBLISHED, FAILED
    }
}
