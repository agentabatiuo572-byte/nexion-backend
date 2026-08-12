package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers the H8 sandbox surface only in one explicitly isolated runtime profile. */
public final class H8AcceptanceSandboxProfileCondition implements Condition {
    public static boolean isStrictIsolatedProfile(String... activeProfiles) {
        return FundsSandboxProfileGuard.isStrictIsolatedProfile(activeProfiles);
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isStrictIsolatedProfile(context.getEnvironment().getActiveProfiles());
    }
}
