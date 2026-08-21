package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers wheel sandbox startup checks only for one supported isolated profile. */
public final class WheelSandboxProfileCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String[] profiles = context.getEnvironment().getActiveProfiles();
        return FundsSandboxProfileGuard.isStrictIsolatedProfile(profiles);
    }
}
