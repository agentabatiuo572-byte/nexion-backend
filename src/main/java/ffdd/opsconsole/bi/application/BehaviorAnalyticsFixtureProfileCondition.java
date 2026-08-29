package ffdd.opsconsole.bi.application;

import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers the L6 fixture writer only in one explicitly isolated runtime profile. */
final class BehaviorAnalyticsFixtureProfileCondition implements Condition {
    private static final Set<String> ALLOWED_PROFILES = Set.of("test");

    static boolean isStrictIsolatedProfile(String... activeProfiles) {
        return activeProfiles != null
                && activeProfiles.length == 1
                && ALLOWED_PROFILES.contains(activeProfiles[0]);
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isStrictIsolatedProfile(context.getEnvironment().getActiveProfiles());
    }
}
