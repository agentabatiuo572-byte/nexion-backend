package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Blocks every shared App-support path whenever sandbox facts are in scope. */
@Component
@RequiredArgsConstructor
public class ProductionSupportPathGuard {
    private final Environment environment;
    private final SupportAcceptanceSandboxMapper mapper;

    public void requireAllowed(Long userId) {
        if (isolatedProfile()) {
            throw new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        }
        // A sandbox-marked account never writes or reads the production support facts,
        // even if a client deliberately bypasses the acceptance route selection.
        if (userId != null && Integer.valueOf(1).equals(mapper.sandboxUser(userId))) {
            throw new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        }
    }

    /** Official Ops support writes have no acceptance fallback: use sandbox routes only. */
    public void requireOpsWriteAllowed() {
        if (isolatedProfile()) throw new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN");
    }

    private boolean isolatedProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "acceptance".equals(profile) || "test".equals(profile) || "local-sandbox".equals(profile));
    }

    /** Background jobs may touch official support facts only in default/production mode. */
    public boolean productionSupportAutomationAllowed() {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 || (active.length == 1 && "production".equals(active[0]));
    }
}
