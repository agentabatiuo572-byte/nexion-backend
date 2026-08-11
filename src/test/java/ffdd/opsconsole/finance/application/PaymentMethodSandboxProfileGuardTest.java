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
        environment.setActiveProfiles("production");
        var guard = new PaymentMethodSandboxProfileGuard(properties, environment);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_METHOD_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void acceptanceProfileCanUseExplicitlyIsolatedSandbox() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("acceptance");
        var guard = new PaymentMethodSandboxProfileGuard(properties, environment);

        guard.afterPropertiesSet();
        assertThat(guard.sourceEnvironment()).isEqualTo("SANDBOX");
    }

    @Test
    void productionCannotBeSmuggledAlongsideTestProfile() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production", "test");

        assertThatThrownBy(() -> new PaymentMethodSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_METHOD_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }
}
