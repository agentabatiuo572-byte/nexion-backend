package ffdd.opsconsole.auth.application;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one registration attempt in an independent physical transaction. A
 * caller may retry only after this template has completed its rollback, so an
 * OTP consumption, account, wallet, outbox fact and first session never leak
 * from a deadlock-loser attempt into the next attempt.
 */
@Component
@RequiredArgsConstructor
public class AppUserRegistrationTransactionExecutor {
    private final PlatformTransactionManager transactionManager;

    public <T> T execute(Supplier<T> attempt) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> attempt.get());
    }
}
