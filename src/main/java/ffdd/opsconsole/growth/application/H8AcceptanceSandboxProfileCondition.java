package ffdd.opsconsole.growth.application;

import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers the H8 sandbox surface only in one explicitly isolated runtime profile. */
public final class H8AcceptanceSandboxProfileCondition implements Condition {
    private static final Set<String> ALLOWED_PROFILES = Set.of("test", "acceptance", "local-sandbox");

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
