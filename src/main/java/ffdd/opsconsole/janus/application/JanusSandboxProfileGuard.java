package ffdd.opsconsole.janus.application;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Prevents the Janus fixture executor from being enabled outside isolated development profiles. */
@Component
@RequiredArgsConstructor
public class JanusSandboxProfileGuard {
    private static final Set<String> ALLOWED_SANDBOX_PROFILES = Set.of("dev", "test");

    private final Environment environment;
    @Value("${nexion.janus.executor.mode:PRODUCTION}")
    private final String mode;

    @PostConstruct
    public void validateProfileBoundary() {
        if (!"SANDBOX".equals(executionEnvironment())) return;
        Set<String> active = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (active.size() != 1 || !ALLOWED_SANDBOX_PROFILES.containsAll(active)) {
            throw new IllegalStateException("JANUS_SANDBOX_PROFILE_FORBIDDEN");
        }
    }

    public String executionEnvironment() {
        return "SANDBOX".equalsIgnoreCase(mode == null ? "" : mode.trim()) ? "SANDBOX" : "PRODUCTION";
    }
}
