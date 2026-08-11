package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CregisSandboxProfileGuardTest {
    @Test
    void productionProfileCannotStartLocalPayoutSandbox() {
        CregisProperties properties = new CregisProperties();
        properties.setMode(CregisProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> new CregisSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CREGIS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void productionCannotBeSmuggledAlongsideTestProfile() {
        CregisProperties properties = new CregisProperties();
        properties.setMode(CregisProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production", "test");

        assertThatThrownBy(() -> new CregisSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CREGIS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }
}
