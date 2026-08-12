package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Acceptance-only administration surface for the isolated commerce payment rail. */
public final class CommerceAcceptanceSandboxProfileCondition implements Condition {
    public static boolean isStrictIsolatedProfile(String... activeProfiles) {
        return FundsSandboxProfileGuard.isStrictIsolatedProfile(activeProfiles);
    }

    public static boolean isEnabled(String mode, String... activeProfiles) {
        return "LOCAL_SANDBOX".equals(mode) && isStrictIsolatedProfile(activeProfiles);
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isEnabled(context.getEnvironment().getProperty("nexion.finance.funds-sandbox.mode"),
                context.getEnvironment().getActiveProfiles());
    }
}
