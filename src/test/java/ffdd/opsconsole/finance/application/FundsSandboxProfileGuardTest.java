package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
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
        assertThat(guard.isLocalSandboxEnabled()).isTrue();
    }

    @Test
    void onlyOneExplicitIsolatedProfileCanEnableLocalFundsSandbox() {
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("acceptance")).isTrue();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("test")).isTrue();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("local-sandbox")).isTrue();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile()).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile((String[]) null)).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("production")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("unknown")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("acceptance", "test")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("acceptance", "production")).isFalse();
    }

    @Test
    void mixedOrUnknownProfilesCannotStartLocalFundsSandbox() {
        for (String[] profiles : Stream.<String[]>of(
                new String[] {"acceptance", "test"},
                new String[] {"acceptance", "production"},
                new String[] {"local-sandbox", "unknown"},
                new String[] {"unknown"},
                new String[0]).toList()) {
            FundsSandboxProperties properties = new FundsSandboxProperties();
            properties.setMode(FundsSandboxProperties.Mode.LOCAL_SANDBOX);
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);

            assertThatThrownBy(() -> new FundsSandboxProfileGuard(properties, environment).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("FUNDS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
        }
    }

    @Test
    void disabledOrProviderModesNeverAdvertiseTheSandboxAsEnabled() {
        FundsSandboxProperties properties = new FundsSandboxProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("acceptance");
        FundsSandboxProfileGuard guard = new FundsSandboxProfileGuard(properties, environment);

        assertThat(guard.isLocalSandboxEnabled()).isFalse();
        properties.setMode(FundsSandboxProperties.Mode.PROVIDER);
        assertThat(guard.isLocalSandboxEnabled()).isFalse();
    }
}
