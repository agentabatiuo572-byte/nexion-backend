package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class G2AcceptanceSandboxProfileGuardTest {
    @Test
    void enablesOnlyTheExplicitSingleAcceptanceProfile() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] { "dev" });

        G2AcceptanceSandboxProfileGuard guard = new G2AcceptanceSandboxProfileGuard(environment, "ENABLED");

        assertThat(guard.available()).isTrue();
        assertThat(guard.source()).isEqualTo("mock");
        assertThat(guard.sourceEnvironment()).isEqualTo("SANDBOX");
    }

    @Test
    void failsClosedForDisabledOrMixedProfiles() {
        Environment mixed = mock(Environment.class);
        when(mixed.getActiveProfiles()).thenReturn(new String[] { "dev", "prod" });

        G2AcceptanceSandboxProfileGuard guard = new G2AcceptanceSandboxProfileGuard(mixed, "ENABLED");

        assertThat(guard.available()).isFalse();
        assertThatThrownBy(guard::requireAvailable)
                .hasMessageContaining("G2_ACCEPTANCE_SANDBOX_PROFILE_FORBIDDEN");
    }
}
