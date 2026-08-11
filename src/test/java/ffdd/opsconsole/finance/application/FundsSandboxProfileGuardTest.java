package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FundsSandboxProfileGuardTest {
    @Test
    void productionCannotStartWithLocalFundsSandbox() {
        FundsSandboxProperties properties = new FundsSandboxProperties();
        properties.setMode(FundsSandboxProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> new FundsSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FUNDS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void acceptanceCanRunExplicitServerSandbox() {
        FundsSandboxProperties properties = new FundsSandboxProperties();
        properties.setMode(FundsSandboxProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("acceptance");
        FundsSandboxProfileGuard guard = new FundsSandboxProfileGuard(properties, environment);

        guard.afterPropertiesSet();

        assertThat(guard.source()).isEqualTo("mock");
        assertThat(guard.sourceEnvironment()).isEqualTo("SANDBOX");
    }
}
