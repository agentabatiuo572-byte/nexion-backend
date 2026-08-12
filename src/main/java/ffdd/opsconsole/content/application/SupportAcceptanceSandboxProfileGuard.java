package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Arrays;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The M-support acceptance data plane is fail-closed.  A mixed profile is
 * production-adjacent and is never permitted to select sandbox tables.
 */
@Component
@Profile({"acceptance", "test", "local-sandbox"})
@RequiredArgsConstructor
public class SupportAcceptanceSandboxProfileGuard {
    private final Environment environment;
    @Value("${nexion.support.acceptance-sandbox.mode:DISABLED}")
    private final String mode;

    public boolean available() {
        String[] active = environment.getActiveProfiles();
        return "ENABLED".equalsIgnoreCase(mode == null ? "" : mode.trim())
                && active.length == 1 && ("acceptance".equals(active[0]) || "test".equals(active[0]) || "local-sandbox".equals(active[0]));
    }

    public void requireAvailable() {
        if (!available()) throw new BizException(409, "SUPPORT_ACCEPTANCE_SANDBOX_PROFILE_FORBIDDEN");
    }

    public Set<String> activeProfiles() { return Set.copyOf(Arrays.asList(environment.getActiveProfiles())); }
}
