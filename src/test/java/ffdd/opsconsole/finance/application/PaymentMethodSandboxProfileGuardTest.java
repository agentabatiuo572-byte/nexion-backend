package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PaymentMethodSandboxProfileGuardTest {
    @Test
    void productionProfileCannotStartWithLocalSandboxMisconfiguration() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var guard = new PaymentMethodSandboxProfileGuard(properties, environment);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_METHOD_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void developmentProfileCanUseExplicitlyIsolatedSandbox() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var guard = new PaymentMethodSandboxProfileGuard(properties, environment);

        guard.afterPropertiesSet();
        assertThat(guard.sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(guard.requireRunId()).isEqualTo("local-dev");
    }

    @Test
    void legacyLocalSandboxProfileIsRejected() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-sandbox");

        var guard = new PaymentMethodSandboxProfileGuard(properties, environment);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_METHOD_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void multipleAllowedSandboxProfilesAreRejected() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "test");

        assertThatThrownBy(() -> new PaymentMethodSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_METHOD_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void productionCannotBeSmuggledAlongsideTestProfile() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "test");

        assertThatThrownBy(() -> new PaymentMethodSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_METHOD_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void unknownOrMixedProfilesAreNotCanonicalProduction() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "test");
        var guard = new PaymentMethodSandboxProfileGuard(properties, environment);

        assertThat(guard.isStrictProductionProfile()).isFalse();
        assertThat(guard.isStrictIsolatedProfile()).isFalse();
    }
}
