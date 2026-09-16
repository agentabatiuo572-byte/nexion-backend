package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Reversible public TEST exception for timestamp validation only; real risk evidence remains required. */
@Component
@RequiredArgsConstructor
public class WithdrawalRiskTimePolicy {
    public static final String KEY = "withdrawal.k4_time_validation_enabled";
    private final Environment environment;
    private final PlatformConfigFacade config;

    public boolean enabled() {
        if (!FundsSandboxProfileGuard.isStrictDevelopmentProfile(environment.getActiveProfiles())
                || !environment.getProperty("nexion.deployment.public-test", Boolean.class, false)) return true;
        // Missing, malformed and unavailable configuration must never silently disable validation.
        return !config.activeValue(KEY).map(String::trim).filter("false"::equalsIgnoreCase).isPresent();
    }
}
