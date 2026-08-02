package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class K4ScoreBackfillTransactionExecutorTest {
    @Test
    void commitsOneK4ProjectionAttemptInItsOwnReadCommittedTransaction() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        K4ScoreBackfillTransactionExecutor executor = new K4ScoreBackfillTransactionExecutor(manager);

        assertThat(executor.execute(() -> "projected")).isEqualTo("projected");
        assertThat(manager.begun).isEqualTo(1);
        assertThat(manager.committed).isEqualTo(1);
        assertThat(manager.rolledBack).isZero();
        assertThat(manager.propagation).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(manager.isolation).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void rollsBackTheWholeProjectionAttemptBeforeRetry() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        K4ScoreBackfillTransactionExecutor executor = new K4ScoreBackfillTransactionExecutor(manager);

        assertThatThrownBy(() -> executor.execute(() -> {
            throw new CannotAcquireLockException("score row busy");
        })).isInstanceOf(CannotAcquireLockException.class);
        assertThat(manager.committed).isZero();
        assertThat(manager.rolledBack).isEqualTo(1);
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private int begun;
        private int committed;
        private int rolledBack;
        private int propagation;
        private int isolation;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            propagation = definition.getPropagationBehavior();
            isolation = definition.getIsolationLevel();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }
}
