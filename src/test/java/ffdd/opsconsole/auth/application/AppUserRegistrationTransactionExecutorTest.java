package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AppUserRegistrationTransactionExecutorTest {
    @Test
    void commitsSuccessfulRegistrationAttemptInItsOwnTransaction() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        AppUserRegistrationTransactionExecutor executor = new AppUserRegistrationTransactionExecutor(manager);

        String result = executor.execute(() -> "registered");

        assertThat(result).isEqualTo("registered");
        assertThat(manager.begun).isEqualTo(1);
        assertThat(manager.committed).isEqualTo(1);
        assertThat(manager.rolledBack).isZero();
        assertThat(manager.propagation).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void rollsBackTheWholeAttemptBeforeTheServiceMayRetryALockConflict() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        AppUserRegistrationTransactionExecutor executor = new AppUserRegistrationTransactionExecutor(manager);

        assertThatThrownBy(() -> executor.execute(() -> {
            throw new CannotAcquireLockException("lock timeout");
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(manager.begun).isEqualTo(1);
        assertThat(manager.committed).isZero();
        assertThat(manager.rolledBack).isEqualTo(1);
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private int begun;
        private int committed;
        private int rolledBack;
        private int propagation;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            propagation = definition.getPropagationBehavior();
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
