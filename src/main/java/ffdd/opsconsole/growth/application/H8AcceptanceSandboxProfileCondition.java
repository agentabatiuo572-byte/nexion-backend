package ffdd.opsconsole.growth.application;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers the H8 sandbox surface only in one explicitly isolated runtime profile. */
public final class H8AcceptanceSandboxProfileCondition implements Condition {
    public static boolean isStrictIsolatedProfile(String... activeProfiles) {
        return activeProfiles != null
                && activeProfiles.length == 1
                && activeProfiles[0] != null
                && "test".equalsIgnoreCase(activeProfiles[0].trim());
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isStrictIsolatedProfile(context.getEnvironment().getActiveProfiles());
    }
}
