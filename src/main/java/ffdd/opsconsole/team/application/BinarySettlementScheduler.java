package ffdd.opsconsole.team.application;

import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.shared.config.VietnamBusinessPeriod;
import ffdd.opsconsole.team.mapper.BinarySettlementScheduleMapper;
import ffdd.opsconsole.team.mapper.BinarySettlementScheduleMapper.PendingBatch;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
public class BinarySettlementScheduler {
    private static final String LOCK_NAME = "CONCAT('nx:f3:auto:', LEFT(SHA2(DATABASE(), 256), 32))";
    private static final String ACQUIRE_LOCK = "SELECT GET_LOCK(" + LOCK_NAME + ", 0)";
    private static final String RELEASE_LOCK = "SELECT RELEASE_LOCK(" + LOCK_NAME + ")";
    private static final int OWNER_PAGE_SIZE = 100;
    private static final String AUTO_DISABLED = "F3_AUTO_SETTLEMENT_DISABLED";
    private final HikariDataSource dataSource;
    private final BinarySettlementScheduleMapper mapper;
    private final BinaryCommissionSettlementService settlementService;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private volatile boolean closing;

    public BinarySettlementScheduler(HikariDataSource dataSource, BinarySettlementScheduleMapper mapper,
            BinaryCommissionSettlementService settlementService, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.settlementService = settlementService;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @EventListener(ContextClosedEvent.class)
    public void contextClosed() {
        closing = true;
    }

    @Scheduled(fixedDelayString = "${nexion.f3.auto-settlement-poll-ms:60000}")
    public void settleClosedPeriods() {
        if (closing) return;
        // Keep the schema-wide advisory lock on a dedicated session without an open transaction.
        try (Connection connection = dataSource.getConnection()) {
            try {
                if (!executeLockCommand(connection, ACQUIRE_LOCK)) return;
                try {
                    settleDueBatches();
                } finally {
                    if (!executeLockCommand(connection, RELEASE_LOCK)) {
                        throw new SQLException("F3 automatic settlement lock was lost");
                    }
                }
            } catch (SQLException ex) {
                // Never return a session with uncertain lock ownership to the pool.
                dataSource.evictConnection(connection);
                throw ex;
            }
        } catch (SQLException | RuntimeException ex) {
            log.error("F3 automatic settlement scheduler failed", ex);
        }
    }

    private boolean executeLockCommand(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            if (rows.next()) {
                int result = rows.getInt(1);
                if (!rows.wasNull()) return result == 1;
            }
            throw new SQLException("MySQL could not determine F3 automatic settlement lock ownership");
        }
    }

    private void settleDueBatches() {
        var settings = mapper.readSettings();
        if (settings == null || settings.enabledAt() == null
                || !Boolean.TRUE.equals(BinarySettlementPolicyProvider.parsePaused(settings.automatic()))) return;

        LocalDate today = VietnamBusinessPeriod.dateAt(clock.instant());
        LocalDate firstActiveDate = businessDate(settings.enabledAt());
        transaction.executeWithoutResult(status -> {
            if (mapper.cancelOldBatches(firstActiveDate) > 0) mapper.deleteCanceledRetries();
        });

        LocalDate firstNewDate = firstActiveDate;
        LocalDate latest = mapper.latestBatchDate();
        if (latest != null && !latest.isBefore(firstNewDate)) firstNewDate = latest.plusDays(1);
        LocalDate next = switch (settings.cadence().trim()) {
            case "每日" -> firstNewDate;
            case "每周" -> firstNewDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            case "每月" -> firstNewDate.with(TemporalAdjusters.lastDayOfMonth());
            default -> null;
        };
        if (next == null) return;
        if (next.isBefore(today)) mapper.createBatch(next);

        for (PendingBatch batch : mapper.pendingBatches(firstActiveDate, today)) {
            if (closing || !settleBatch(batch)) break;
        }
    }

    private LocalDate businessDate(String enabledAt) {
        return VietnamBusinessPeriod.dateAt(Instant.parse(enabledAt));
    }

    private boolean settleBatch(PendingBatch batch) {
        LocalDate date = batch.settlementDate();
        String block = settlementService.automaticBatchBlock(date);
        if (block != null) {
            recordBlock(date, block);
            return false;
        }
        // Retry owners from a prior pass first. Exceptions in this pass remain queued for the next one.
        if (!settleOwners(date, 0, true) || !settleOwners(date, batch.lastOwnerId(), false)) return false;
        if (mapper.completeBatch(date) == 1) return true;
        // Later dates may consume volume still owed to an owner whose earlier settlement failed.
        mapper.recordError(date, "F3_AUTO_OWNER_RETRY");
        return false;
    }

    private boolean settleOwners(LocalDate date, long afterOwnerId, boolean retry) {
        while (!closing) {
            List<Long> owners = retry ? mapper.retryOwners(date, afterOwnerId, OWNER_PAGE_SIZE)
                    : mapper.listOwners(afterOwnerId, OWNER_PAGE_SIZE);
            if (owners.isEmpty()) return true;
            for (long ownerId : owners) {
                if (closing) return false;
                String block = settleOwner(date, ownerId, retry);
                if (block != null) {
                    recordBlock(date, block);
                    return false;
                }
                afterOwnerId = ownerId;
            }
        }
        return false;
    }

    private String settleOwner(LocalDate date, long ownerId, boolean retry) {
        try {
            return transaction.execute(status -> {
                var activation = mapper.lockActivation();
                if (activation == null || activation.enabledAt() == null
                        || !Boolean.TRUE.equals(BinarySettlementPolicyProvider.parsePaused(activation.automatic()))
                        || date.isBefore(businessDate(activation.enabledAt()))) return AUTO_DISABLED;
                int claimed = retry ? mapper.claimRetry(date, ownerId) : mapper.advanceCursor(date, ownerId);
                if (claimed == 0) return null;
                var result = settlementService.settle(ownerId, date);
                if (result.reason() != null && BinaryCommissionSettlementService.isBatchBlock(result.reason())) {
                    status.setRollbackOnly();
                    return result.reason();
                }
                return null;
            });
        } catch (BinarySettlementPolicyProvider.PolicyBlocked ex) {
            return ex.getMessage();
        } catch (RuntimeException ex) {
            if (retry) {
                log.debug("F3 automatic settlement owner retry failed date={} ownerId={}", date, ownerId, ex);
            } else {
                // Settlement and cursor rolled back together. Advance the scan only after saving the retry.
                boolean queued = Boolean.TRUE.equals(transaction.execute(status -> {
                    if (mapper.advanceCursor(date, ownerId) != 1) return false;
                    mapper.enqueueRetry(date, ownerId);
                    return true;
                }));
                if (queued) {
                    log.warn("F3 automatic settlement owner queued for retry date={} ownerId={}", date, ownerId, ex);
                }
            }
            return null;
        }
    }

    private void recordBlock(LocalDate date, String reason) {
        if (mapper.recordError(date, reason) == 1) {
            log.warn("F3 automatic settlement batch blocked date={} reason={}", date, reason);
        }
    }
}
