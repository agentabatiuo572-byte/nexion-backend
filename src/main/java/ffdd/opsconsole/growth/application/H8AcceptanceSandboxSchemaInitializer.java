package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Refuses an acceptance start if its isolated H8 proof ledger was not migrated. */
@Component
@Conditional(H8AcceptanceSandboxProfileCondition.class)
@RequiredArgsConstructor
public class H8AcceptanceSandboxSchemaInitializer implements ApplicationRunner {
    private final ReferralRewardMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        if (mapper.h8AcceptanceSandboxSchemaColumns() != 41) {
            throw new IllegalStateException("H8_ACCEPTANCE_SANDBOX_SCHEMA_MIGRATION_REQUIRED: "
                    + "apply scripts/migrations/20260811_h8_acceptance_sandbox_referral_ledger.sql and "
                    + "20260812_h8_acceptance_sandbox_run_scope.sql before startup");
        }
    }
}
