package ffdd.opsconsole.shared.idempotency;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

class AdminIdempotencyExpiryRecoveryTest {
    @Test
    void candidateStartupReconcilesOnlyExpiredProcessingCrashRemnants() {
        AdminIdempotencyExpiryTransitionExecutor executor =
                org.mockito.Mockito.mock(AdminIdempotencyExpiryTransitionExecutor.class);
        AdminIdempotencyExpiryRecovery recovery = new AdminIdempotencyExpiryRecovery(executor);

        recovery.reconcileExpiredProcessing();

        verify(executor).markExpiredProcessingUnknownBatch(200);
    }

    @Test
    void schedulerRetriesOnlyTheIndependentRecoveryTransactionAfterRareDeadlock() {
        AdminIdempotencyExpiryTransitionExecutor executor =
                org.mockito.Mockito.mock(AdminIdempotencyExpiryTransitionExecutor.class);
        org.mockito.Mockito.when(executor.markExpiredProcessingUnknownBatch(200))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", new RuntimeException("mysql")))
                .thenReturn(1);
        AdminIdempotencyExpiryRecovery recovery = new AdminIdempotencyExpiryRecovery(executor);

        recovery.reconcileExpiredProcessingOnSchedule();

        verify(executor, times(2)).markExpiredProcessingUnknownBatch(200);
    }
}
