package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JanusSandboxProfileGuardTest {
    @Test
    void productionProfileRejectsSandboxExecutorModeAtStartup() {
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(
                new MockEnvironment().withProperty("spring.profiles.active", "prod"), "SANDBOX");

        assertThatThrownBy(guard::validateProfileBoundary)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JANUS_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void internalTestProfileExposesExplicitSandboxMarker() {
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(
                new MockEnvironment().withProperty("spring.profiles.active", "test"), "SANDBOX");

        guard.validateProfileBoundary();

        assertThat(guard.executionEnvironment()).isEqualTo("SANDBOX");
    }

    @Test
    void legacyLocalSandboxProfileIsRejected() {
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(
                new MockEnvironment().withProperty("spring.profiles.active", "local-sandbox"), "SANDBOX");

        assertThatThrownBy(guard::validateProfileBoundary)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JANUS_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void multipleSandboxProfilesAreRejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test", "dev");
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(environment, "SANDBOX");

        assertThatThrownBy(guard::validateProfileBoundary)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JANUS_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void productionModeRemainsProductionInEveryProfile() {
        JanusSandboxProfileGuard guard = new JanusSandboxProfileGuard(
                new MockEnvironment().withProperty("spring.profiles.active", "prod"), "PRODUCTION");

        guard.validateProfileBoundary();

        assertThat(guard.executionEnvironment()).isEqualTo("PRODUCTION");
    }
}
