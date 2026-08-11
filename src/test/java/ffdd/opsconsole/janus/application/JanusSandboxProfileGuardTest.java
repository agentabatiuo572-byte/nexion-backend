package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JanusSandboxProfileGuardTest {
    @Test
    void productionProfileRejectsSandboxExecutorModeAtStartup() {
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(
                new MockEnvironment().withProperty("spring.profiles.active", "production"), "SANDBOX");

        assertThatThrownBy(guard::validateProfileBoundary)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JANUS_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void acceptanceProfileExposesExplicitSandboxMarker() {
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(
                new MockEnvironment().withProperty("spring.profiles.active", "acceptance"), "SANDBOX");

        guard.validateProfileBoundary();

        assertThat(guard.executionEnvironment()).isEqualTo("SANDBOX");
    }

    @Test
    void productionModeRemainsProductionInEveryProfile() {
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(
                new MockEnvironment().withProperty("spring.profiles.active", "production"), "PRODUCTION");

        guard.validateProfileBoundary();

        assertThat(guard.executionEnvironment()).isEqualTo("PRODUCTION");
    }
}
