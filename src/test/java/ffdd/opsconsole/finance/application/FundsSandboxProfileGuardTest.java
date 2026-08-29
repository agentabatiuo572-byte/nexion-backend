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
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new FundsSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FUNDS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void developmentCannotRunTheRetiredLocalFundsSandbox() {
        FundsSandboxProperties properties = new FundsSandboxProperties();
        properties.setMode(FundsSandboxProperties.Mode.LOCAL_SANDBOX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatThrownBy(() -> new FundsSandboxProfileGuard(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FUNDS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
    }

    @Test
    void onlyOneExplicitIsolatedProfileCanEnableLocalFundsSandbox() {
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("dev")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("test")).isTrue();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("local-sandbox")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile()).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile((String[]) null)).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("prod")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("unknown")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("dev", "test")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictIsolatedProfile("dev", "prod")).isFalse();
    }

    @Test
    void mixedOrUnknownProfilesCannotStartLocalFundsSandbox() {
        for (String[] profiles : Stream.<String[]>of(
                new String[] {"dev", "test"},
                new String[] {"dev", "prod"},
                new String[] {"dev", "unknown"},
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
        environment.setActiveProfiles("dev");
        FundsSandboxProfileGuard guard = new FundsSandboxProfileGuard(properties, environment);

        assertThat(guard.isLocalSandboxEnabled()).isFalse();
        properties.setMode(FundsSandboxProperties.Mode.PROVIDER);
        assertThat(guard.isLocalSandboxEnabled()).isFalse();
    }

    @Test
    void singleDevOrProdProfileCanUseCanonicalFunds() {
        assertThat(FundsSandboxProfileGuard.isStrictProductionProfile()).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictProductionProfile("prod")).isTrue();
        assertThat(FundsSandboxProfileGuard.isStrictProductionProfile("default")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictProductionProfile("unknown")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictProductionProfile("prod", "test")).isFalse();
        assertThat(FundsSandboxProfileGuard.isStrictProductionProfile("dev")).isTrue();
    }
}
