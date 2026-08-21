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
                properties, new MockEnvironment().withProperty("spring.profiles.active", "prod"))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYOUT_VND_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void localSandboxIsAllowedInDevelopment() {
        PayoutVndProviderProperties properties = new PayoutVndProviderProperties();
        properties.setMode(PayoutVndProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatCode(() -> new PayoutVndSandboxProfileGuard(
                properties, environment)
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void localSandboxIsRejectedInLegacyLocalSandboxProfile() {
        PayoutVndProviderProperties properties = new PayoutVndProviderProperties();
        properties.setMode(PayoutVndProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-sandbox");

        assertThatThrownBy(() -> new PayoutVndSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYOUT_VND_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void localSandboxIsRejectedWhenMultipleProfilesAreActive() {
        PayoutVndProviderProperties properties = new PayoutVndProviderProperties();
        properties.setMode(PayoutVndProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "test");

        assertThatThrownBy(() -> new PayoutVndSandboxProfileGuard(
                properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYOUT_VND_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }
}
