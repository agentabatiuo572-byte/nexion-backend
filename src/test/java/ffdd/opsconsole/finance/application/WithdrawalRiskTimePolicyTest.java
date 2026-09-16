package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WithdrawalRiskTimePolicyTest {
    @Test void disablingIsExplicitAndRestrictedToPublicTestDev() {
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue(WithdrawalRiskTimePolicy.KEY)).thenReturn(Optional.of("false"));
        var env = new MockEnvironment().withProperty("nexion.deployment.public-test", "true");
        var policy = new WithdrawalRiskTimePolicy(env, config);
        for (String[] profiles : new String[][]{{"prod"}, {"test"}, {"dev", "prod"}, {}}) {
            env.setActiveProfiles(profiles);
            assertThat(policy.enabled()).isTrue();
        }
        env.setActiveProfiles("dev");
        assertThat(policy.enabled()).isFalse();
        env.setProperty("nexion.deployment.public-test", "false");
        assertThat(policy.enabled()).isTrue();
        env.setProperty("nexion.deployment.public-test", "true");
        for (String value : new String[]{"true", "garbage", "", "0"}) {
            when(config.activeValue(WithdrawalRiskTimePolicy.KEY)).thenReturn(Optional.of(value));
            assertThat(policy.enabled()).isTrue();
        }
        when(config.activeValue(WithdrawalRiskTimePolicy.KEY)).thenReturn(Optional.empty());
        assertThat(policy.enabled()).isTrue();
    }
}
