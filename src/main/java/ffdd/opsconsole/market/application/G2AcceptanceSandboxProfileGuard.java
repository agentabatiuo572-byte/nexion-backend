package ffdd.opsconsole.market.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Arrays;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The fixture executor is deliberately unavailable unless the server was started as the single
 * {@code dev} profile with the explicit opt-in from application-dev.yml. A mixed
 * profile must be treated as production-adjacent and is therefore closed, not "best effort".
 */
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class G2AcceptanceSandboxProfileGuard {
    private final Environment environment;
    @Value("${nexion.market.g2-acceptance-sandbox.mode:DISABLED}")
    private final String mode;

    public boolean available() {
        String[] active = environment.getActiveProfiles();
        return "ENABLED".equalsIgnoreCase(mode == null ? "" : mode.trim())
                && active.length == 1 && ("dev".equals(active[0]) || "test".equals(active[0]));
    }

    public void requireAvailable() {
        if (!available()) {
            throw new BizException(404, "G2_ACCEPTANCE_SANDBOX_PROFILE_FORBIDDEN");
        }
    }

    public String source() {
        return "mock";
    }

    public String sourceEnvironment() {
        return "SANDBOX";
    }

    public Set<String> activeProfiles() {
        return Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
    }
}
