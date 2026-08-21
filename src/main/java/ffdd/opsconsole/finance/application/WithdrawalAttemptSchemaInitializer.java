package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the durable ambiguity fence before withdrawal traffic is accepted. */
@Component
@RequiredArgsConstructor
public class WithdrawalAttemptSchemaInitializer implements ApplicationRunner {
    private final AppWithdrawalMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        mapper.createWithdrawalAttemptTable();
        if (mapper.withdrawalAttemptStatusCheckCount() != 1) {
            throw new IllegalStateException("WITHDRAWAL_ATTEMPT_STATUS_CHECK_MISSING: apply "
                    + "scripts/migrations/20260816_withdrawal_attempt_authority.sql before startup");
        }
    }
}
