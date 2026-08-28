package ffdd.opsconsole.content.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.exception.BizException;
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
                && exactProfile("test");
    }

    public boolean isStrictDevelopmentRuntime() {
        return FundsSandboxProfileGuard.isStrictDevelopmentProfile(environment.getActiveProfiles());
    }

    public String developmentCountryCode() {
        return environment.getProperty("nexion.auth.development-passkey-account.country-code", "").trim();
    }

    public String developmentPhone() {
        return environment.getProperty("nexion.auth.development-passkey-account.phone", "").trim();
    }

    public void requireEnabled(String sourceEnvironment) {
        String[] activeProfiles = environment.getActiveProfiles();
        if (exactProfile("test")) {
            if (!"SANDBOX".equals(sourceEnvironment)) {
                throw new IllegalStateException("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED");
            }
            return;
        }
        if (FundsSandboxProfileGuard.isStrictDevelopmentProfile(activeProfiles)) {
            if (!"PRODUCTION".equals(sourceEnvironment)) {
                throw new IllegalStateException("LEARNING_DEVELOPMENT_PRODUCTION_FACT_REQUIRED");
            }
            return;
        }
        if (!FundsSandboxProfileGuard.isStrictProductionProfile(activeProfiles)) {
            throw new BizException(503, "LEARNING_ACCEPTANCE_PROFILE_INVALID");
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
        if (exactProfile("test")) {
            throw new IllegalStateException("LEARNING_ACCEPTANCE_PRODUCTION_MUTATION_FORBIDDEN");
        }
        if (FundsSandboxProfileGuard.isStrictDevelopmentProfile(activeProfiles)) return;
        if (!FundsSandboxProfileGuard.isStrictProductionProfile(activeProfiles)) {
            throw new BizException(503, "LEARNING_ACCEPTANCE_PROFILE_INVALID");
        }
    }

    private boolean exactProfile(String expected) {
        String[] active = environment.getActiveProfiles();
        return active != null && active.length == 1 && expected.equalsIgnoreCase(String.valueOf(active[0]).trim());
    }
}
