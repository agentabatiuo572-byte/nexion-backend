package ffdd.opsconsole.content.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Allows learning sandbox facts only for a sandbox user in exactly one isolated profile. */
@Component
@RequiredArgsConstructor
public class LearningAcceptanceSandboxGate {
    private final Environment environment;

    public boolean enabled(String sourceEnvironment) {
        return "SANDBOX".equals(sourceEnvironment)
                && FundsSandboxProfileGuard.isStrictIsolatedProfile(environment.getActiveProfiles());
    }

    public void requireEnabled(String sourceEnvironment) {
        String[] activeProfiles = environment.getActiveProfiles();
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(activeProfiles)) {
            if (!"SANDBOX".equals(sourceEnvironment)) {
                throw new IllegalStateException("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED");
            }
            return;
        }
        if (activeProfiles != null && activeProfiles.length > 0
                && !(activeProfiles.length == 1 && "production".equals(activeProfiles[0]))) {
            throw new IllegalStateException("LEARNING_ACCEPTANCE_PROFILE_INVALID");
        }
        if ("SANDBOX".equals(sourceEnvironment)) {
            throw new IllegalStateException("LEARNING_ACCEPTANCE_SANDBOX_PROFILE_FORBIDDEN");
        }
    }

    /**
     * The formal course/i18n catalog is a production fact. In a controlled
     * acceptance profile it must be unreachable before command idempotency,
     * audit, or outbox code can create a durable shared record.
     */
    public void requireProductionMutationAllowed() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(activeProfiles)) {
            throw new IllegalStateException("LEARNING_ACCEPTANCE_PRODUCTION_MUTATION_FORBIDDEN");
        }
        if (activeProfiles != null && activeProfiles.length > 0
                && !(activeProfiles.length == 1 && "production".equals(activeProfiles[0]))) {
            throw new IllegalStateException("LEARNING_ACCEPTANCE_PROFILE_INVALID");
        }
    }
}
