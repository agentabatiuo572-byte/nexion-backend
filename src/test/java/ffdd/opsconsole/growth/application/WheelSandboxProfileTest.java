package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WheelSandboxProfileTest {
    @Test
    void localSandboxRequiresSingleProfileAndRunFence() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "WHEEL-SANDBOX-20260815");
        environment.setActiveProfiles("test");
        WheelSandboxProfile profile = new WheelSandboxProfile(environment);

        assertThat(profile.mode()).isEqualTo(WheelSandboxProfile.Mode.SANDBOX);
        assertThat(profile.requireSandbox(42L).runId()).isEqualTo("WHEEL-SANDBOX-20260815");
    }

    @Test
    void mixedOrUnknownProfilesFailClosed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        WheelSandboxProfile profile = new WheelSandboxProfile(environment);

        assertThat(profile.mode()).isEqualTo(WheelSandboxProfile.Mode.UNKNOWN);
        assertThatThrownBy(profile::requireKnownRuntime)
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                .hasMessageContaining("WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
    }

    @Test
    void legacyDefaultProfileAndMixedProfilesRemainUnknown() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("default");
        WheelSandboxProfile profile = new WheelSandboxProfile(environment);

        assertThat(profile.mode()).isEqualTo(WheelSandboxProfile.Mode.UNKNOWN);
        assertThatThrownBy(profile::requireKnownRuntime)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("WHEEL_RUNTIME_PROFILE_UNSUPPORTED");

        environment.setActiveProfiles("default", "prod");
        assertThat(profile.mode()).isEqualTo(WheelSandboxProfile.Mode.UNKNOWN);
        assertThatThrownBy(profile::requireKnownRuntime)
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                .hasMessageContaining("WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
    }

    @Test
    void developmentIsCanonicalWhileTestKeepsTheIsolatedContract() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "WHEEL-SANDBOX-20260815");
        environment.setActiveProfiles("dev");
        assertThat(new WheelSandboxProfile(environment).mode()).isEqualTo(WheelSandboxProfile.Mode.PRODUCTION);
        environment.setActiveProfiles("test");
        assertThat(new WheelSandboxProfile(environment).mode()).isEqualTo(WheelSandboxProfile.Mode.SANDBOX);
    }
}
