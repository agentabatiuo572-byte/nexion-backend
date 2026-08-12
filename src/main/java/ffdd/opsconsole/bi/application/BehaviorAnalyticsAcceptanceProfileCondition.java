package ffdd.opsconsole.bi.application;

import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers the L6 Sandbox observation surface only in one isolated profile. */
public final class BehaviorAnalyticsAcceptanceProfileCondition implements Condition {
    private static final Set<String> ALLOWED = Set.of("test", "acceptance", "local-sandbox");

    public static boolean isStrictIsolatedProfile(String... activeProfiles) {
        return activeProfiles != null && activeProfiles.length == 1 && ALLOWED.contains(activeProfiles[0]);
    }

    /** Null is fail-closed: only one named profile determines an environment. */
    public static String sourceEnvironmentFor(String... activeProfiles) {
        if (isStrictIsolatedProfile(activeProfiles)) return "SANDBOX";
        return activeProfiles != null && activeProfiles.length == 1 && "production".equals(activeProfiles[0])
                ? "PRODUCTION" : null;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isStrictIsolatedProfile(context.getEnvironment().getActiveProfiles());
    }
}
