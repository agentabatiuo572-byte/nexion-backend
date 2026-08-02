package ffdd.opsconsole.shared.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes expired crash remnants at candidate startup.  UNKNOWN is intentionally
 * not retryable: the original result is no longer knowable, so a caller must
 * investigate rather than replay a potentially side-effecting command.
 */
@Component
@RequiredArgsConstructor
public class AdminIdempotencyExpiryRecovery {
    static final int BATCH_SIZE = 200;
    private static final int MAX_LOCK_RETRY_ATTEMPTS = 2;
    private final AdminIdempotencyExpiryTransitionExecutor transitionExecutor;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileExpiredProcessing() {
        recoverExpiredProcessing();
    }

    @Scheduled(fixedDelayString = "${nexion.idempotency.expiry-recovery-delay-ms:60000}")
    public void reconcileExpiredProcessingOnSchedule() {
        recoverExpiredProcessing();
    }

    /**
     * A SKIP LOCKED claim prevents normal request completion from waiting on
     * this scheduler.  A bounded retry remains only for rare deadlocks caused
     * by unrelated schema/index maintenance; exhaustion is surfaced to the
     * scheduler rather than being silently treated as a successful sweep.
     */
    void recoverExpiredProcessing() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_LOCK_RETRY_ATTEMPTS; attempt++) {
            try {
                transitionExecutor.markExpiredProcessingUnknownBatch(BATCH_SIZE);
                return;
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException ex) {
                lastFailure = ex;
            }
        }
        throw new IllegalStateException("IDEMPOTENCY_EXPIRY_RECOVERY_LOCK_RETRY_EXHAUSTED", lastFailure);
    }
}
