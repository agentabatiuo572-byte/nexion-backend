package ffdd.opsconsole.risk.application;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Gives each K4 synchronization/projection attempt its own bounded physical transaction. */
@Component
@RequiredArgsConstructor
public class K4ScoreBackfillTransactionExecutor {
    private final PlatformTransactionManager transactionManager;

    public <T> T execute(Supplier<T> attempt) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> attempt.get());
    }
}
