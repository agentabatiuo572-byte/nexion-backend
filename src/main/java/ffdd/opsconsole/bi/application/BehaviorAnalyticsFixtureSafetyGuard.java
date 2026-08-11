package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails startup when a mock L6 fixture is accidentally enabled outside an isolated profile. */
@Component
@RequiredArgsConstructor
public class BehaviorAnalyticsFixtureSafetyGuard implements InitializingBean {
    @Value("${nexion.analytics.fixture.enabled:false}")
    private final boolean enabled;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (enabled && !BehaviorAnalyticsFixtureProfileCondition
                .isStrictIsolatedProfile(environment.getActiveProfiles())) {
            throw new BizException(503, "L6_FIXTURE_PROFILE_FORBIDDEN");
        }
    }
}
