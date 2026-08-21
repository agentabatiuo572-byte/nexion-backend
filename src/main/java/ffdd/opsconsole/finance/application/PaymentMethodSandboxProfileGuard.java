package ffdd.opsconsole.finance.application;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentMethodSandboxProfileGuard implements InitializingBean {
    private static final Set<String> ALLOWED_PROFILES = Set.of("dev", "test");
    private final PaymentMethodProviderProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        String[] active = environment.getActiveProfiles();
        if (properties.getMode() == PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX
                && (active.length != 1 || !ALLOWED_PROFILES.contains(active[0]))) {
            throw new IllegalStateException("PAYMENT_METHOD_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
        }
    }

    public String sourceEnvironment() {
        return properties.getMode() == PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX ? "SANDBOX" : "PRODUCTION";
    }

    public boolean isLocalSandboxEnabled() {
        return properties.getMode() == PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX
                && isStrictIsolatedProfile();
    }

    public boolean isStrictIsolatedProfile() {
        return FundsSandboxProfileGuard.isStrictIsolatedProfile(environment.getActiveProfiles());
    }

    public boolean isStrictProductionProfile() {
        return FundsSandboxProfileGuard.isStrictProductionProfile(environment.getActiveProfiles());
    }

    public String requireRunId() {
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID");
        if (runId == null || !runId.trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
            throw new ffdd.opsconsole.shared.exception.BizException(503, "PAYMENT_METHOD_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId.trim();
    }
}
