package ffdd.opsconsole.shared.outbox;

import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Coordinates both schedulers across JVMs while preserving each listener's transaction boundary. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventOutboxDispatcher {
    private static final String LOCK_NAME = EventOutboxMapper.DISPATCH_LOCK_PREFIX + "?)";
    private static final String ACQUIRE_LOCK = "SELECT CASE GET_LOCK(" + LOCK_NAME
            + ", 0) WHEN 1 THEN CONNECTION_ID() WHEN 0 THEN 0 END";
    private static final String RELEASE_LOCK = "SELECT RELEASE_LOCK(" + LOCK_NAME + ")";

    private final HikariDataSource dataSource;
    private final EventOutboxMapper mapper;
    private final EventOutboxService outboxService;
    private final EventConsumerDeliveryProperties deliveryProperties;
    private final ApplicationEventPublisher eventPublisher;

    public Result dispatch(EventOutboxMessage message, String exactEventType) {
        long id = message.getId();
        // Keep the advisory lock on a dedicated session, without an open transaction.
        try (Connection connection = dataSource.getConnection()) {
            try {
                long ownerId = executeLockCommand(connection, ACQUIRE_LOCK, id);
                if (ownerId == 0) {
                    return Result.SKIPPED;
                }
                try {
                    return dispatchOwned(message, exactEventType, ownerId);
                } finally {
                    if (executeLockCommand(connection, RELEASE_LOCK, id) != 1) {
                        throw new SQLException("Outbox dispatch lock was lost");
                    }
                }
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

    private long executeLockCommand(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
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
