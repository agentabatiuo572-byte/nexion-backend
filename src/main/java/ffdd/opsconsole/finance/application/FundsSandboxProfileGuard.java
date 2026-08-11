package ffdd.opsconsole.finance.application;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FundsSandboxProfileGuard implements InitializingBean {
    private static final Set<String> ALLOWED_PROFILES = Set.of("test", "acceptance");
    private final FundsSandboxProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        Set<String> active = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (properties.getMode() == FundsSandboxProperties.Mode.LOCAL_SANDBOX
                && (active.isEmpty() || !ALLOWED_PROFILES.containsAll(active))) {
            throw new IllegalStateException("FUNDS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
        }
    }

    public String source() {
        return properties.getMode() == FundsSandboxProperties.Mode.LOCAL_SANDBOX ? "mock" : "provider";
    }

    public String sourceEnvironment() {
        return properties.getMode() == FundsSandboxProperties.Mode.LOCAL_SANDBOX ? "SANDBOX" : "PRODUCTION";
    }
}
