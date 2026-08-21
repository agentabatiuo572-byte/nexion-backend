package ffdd.opsconsole.team.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * Acceptance-only B1 override for the F2 write walkthrough.
 *
 * <p>The override is deliberately scoped by profile, an explicit switch, and
 * the server-owned RunID. Production/default runtimes can never satisfy all
 * three conditions, so their real coverage gate remains unchanged.</p>
 */
public final class F2SandboxCoveragePolicy {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");

    private F2SandboxCoveragePolicy() { }

    public static boolean isOverrideActive(Environment environment) {
        if (environment == null
                || !FundsSandboxProfileGuard.isStrictIsolatedProfile(environment.getActiveProfiles())
                || !Boolean.parseBoolean(environment.getProperty(
                        "nexion.acceptance.f2.coverage-override-enabled", "false"))) {
            return false;
        }
        String configuredRun = text(environment.getProperty("nexion.acceptance.f2.coverage-override-run-id"));
        String activeRun = text(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID"));
        return configuredRun != null && configuredRun.equals(activeRun) && RUN_ID.matcher(activeRun).matches();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
