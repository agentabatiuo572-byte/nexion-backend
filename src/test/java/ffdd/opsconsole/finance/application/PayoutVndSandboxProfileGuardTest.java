package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PayoutVndSandboxProfileGuardTest {

    @Test
    void localSandboxIsRejectedWithoutAnIsolatedProfile() {
        PayoutVndProviderProperties properties = new PayoutVndProviderProperties();
        properties.setMode(PayoutVndProviderProperties.Mode.LOCAL_SANDBOX);

        assertThatThrownBy(() -> new PayoutVndSandboxProfileGuard(
                properties, new MockEnvironment().withProperty("spring.profiles.active", "production"))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYOUT_VND_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void localSandboxIsAllowedOnlyInAcceptanceOrTest() {
        PayoutVndProviderProperties properties = new PayoutVndProviderProperties();
        properties.setMode(PayoutVndProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("acceptance");

        assertThatCode(() -> new PayoutVndSandboxProfileGuard(
                properties, environment)
                .afterPropertiesSet()).doesNotThrowAnyException();
    }
}
