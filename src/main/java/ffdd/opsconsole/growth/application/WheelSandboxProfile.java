package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Fail-closed runtime fence for the Lucky Spin local sandbox. */
@Component
@RequiredArgsConstructor
public class WheelSandboxProfile {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");

    private final Environment environment;

    public Mode mode() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles == null || profiles.length == 0) return Mode.PRODUCTION;
        if (FundsSandboxProfileGuard.isStrictDevelopmentProfile(profiles)) return Mode.PRODUCTION;
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(profiles)) return Mode.SANDBOX;
        if (FundsSandboxProfileGuard.isStrictProductionProfile(profiles)) return Mode.PRODUCTION;
        return Mode.UNKNOWN;
    }

    public String requireRunId() {
        String runId = environment.getProperty("nexion.wheel.sandbox.run-id",
                environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", ""));
        runId = StringUtils.hasText(runId) ? runId.trim() : "";
        if (!RUN_ID.matcher(runId).matches()) {
            throw new BizException(503, "WHEEL_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId;
    }

    public void requireKnownRuntime() {
        if (mode() == Mode.UNKNOWN) {
            throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
        }
    }

    public Scope requireSandbox(Long userId) {
        if (mode() != Mode.SANDBOX) {
            throw new BizException(503, "WHEEL_SANDBOX_PROFILE_FORBIDDEN");
        }
        if (userId == null || userId <= 0) throw new BizException(403, "USER_SUBJECT_REQUIRED");
        return new Scope(requireRunId(), userId);
    }

    public record Scope(String runId, Long userId) { }

    public enum Mode { PRODUCTION, SANDBOX, UNKNOWN }
}
