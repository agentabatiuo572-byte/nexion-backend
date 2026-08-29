package ffdd.opsconsole.finance.application;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FundsSandboxProfileGuard implements InitializingBean {
    private static final Set<String> ALLOWED_PROFILES = Set.of("test");
    private static final Set<String> CANONICAL_PROFILES = Set.of("dev", "prod");
    private final FundsSandboxProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (properties.getMode() == FundsSandboxProperties.Mode.LOCAL_SANDBOX
                && !isLocalSandboxEnabled()) {
            throw new IllegalStateException("FUNDS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
        }
    }

    /**
     * Single authority for any domain that wants to mutate the isolated funds
     * wallet. LOCAL_SANDBOX is retained only as an in-process automated-test
     * harness. No deployable runtime profile is allowed to expose it.
     */
    public boolean isLocalSandboxEnabled() {
        return properties.getMode() == FundsSandboxProperties.Mode.LOCAL_SANDBOX
                && isStrictIsolatedProfile(environment.getActiveProfiles());
    }

    public static boolean isStrictIsolatedProfile(String... activeProfiles) {
        return activeProfiles != null
                && activeProfiles.length == 1
                && activeProfiles[0] != null
                && ALLOWED_PROFILES.contains(activeProfiles[0].trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isStrictProductionProfile(String... activeProfiles) {
        return activeProfiles != null
                && activeProfiles.length == 1
                && activeProfiles[0] != null
                && CANONICAL_PROFILES.contains(activeProfiles[0].trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isStrictDevelopmentProfile(String... activeProfiles) {
        return activeProfiles != null
                && activeProfiles.length == 1
                && activeProfiles[0] != null
                && "dev".equals(activeProfiles[0].trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isStrictTestProfile(String... activeProfiles) {
        return activeProfiles != null
                && activeProfiles.length == 1
                && activeProfiles[0] != null
                && "test".equals(activeProfiles[0].trim().toLowerCase(java.util.Locale.ROOT));
    }

    public boolean isStrictIsolatedRuntime() {
        return isStrictIsolatedProfile(environment.getActiveProfiles());
    }

    public boolean isStrictProductionRuntime() {
        return isStrictProductionProfile(environment.getActiveProfiles());
    }

    public boolean isStrictDevelopmentRuntime() {
        return isStrictDevelopmentProfile(environment.getActiveProfiles());
    }

    public boolean isStrictTestRuntime() {
        return isStrictTestProfile(environment.getActiveProfiles());
    }

    public String source() {
        return properties.getMode() == FundsSandboxProperties.Mode.LOCAL_SANDBOX ? "mock" : "provider";
    }

    public String sourceEnvironment() {
        return properties.getMode() == FundsSandboxProperties.Mode.LOCAL_SANDBOX ? "SANDBOX" : "PRODUCTION";
    }
}
