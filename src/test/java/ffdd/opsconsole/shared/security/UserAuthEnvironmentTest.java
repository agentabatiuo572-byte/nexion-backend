package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class UserAuthEnvironmentTest {

    @Test
    void activeSpringProfileIsTheOnlyRuntimeAuthority() {
        MockEnvironment dev = new MockEnvironment()
                .withProperty("nexion.runtime.environment", "PROD");
        dev.setActiveProfiles("dev");

        MockEnvironment prod = new MockEnvironment()
                .withProperty("nexion.runtime.environment", "DEV");
        prod.setActiveProfiles("prod");

        assertThat(UserAuthEnvironment.resolve(dev)).contains(UserAuthEnvironment.SANDBOX);
        assertThat(UserAuthEnvironment.resolve(prod)).contains(UserAuthEnvironment.PRODUCTION);
    }

    @Test
    void developmentPasskeyRequestsMustComeFromALoopbackBrowserOrigin() {
        assertThat(UserAuthEnvironment.isLocalDevelopmentRequest(
                "127.0.0.1", "http://127.0.0.1:5173")).isTrue();
        assertThat(UserAuthEnvironment.isLocalDevelopmentRequest(
                "0:0:0:0:0:0:0:1", "http://localhost:5173")).isTrue();
        assertThat(UserAuthEnvironment.isLocalDevelopmentRequest(
                "::1", null)).isTrue();

        assertThat(UserAuthEnvironment.isLocalDevelopmentRequest(
                "192.168.1.20", "http://127.0.0.1:5173")).isFalse();
        assertThat(UserAuthEnvironment.isLocalDevelopmentRequest(
                "127.0.0.1", "http://192.168.1.20:5173")).isFalse();
        assertThat(UserAuthEnvironment.isLocalDevelopmentRequest(
                "127.0.0.1", "null")).isFalse();
    }

    @Test
    void developmentProfileDisablesCallerControlledForwardedAddressRewrites() throws IOException {
        var sources = new YamlPropertySourceLoader().load(
                "application-dev", new ClassPathResource("application-dev.yml"));

        assertThat(sources)
                .anySatisfy(source -> assertThat(source.getProperty("server.forward-headers-strategy"))
                        .isEqualTo("none"));
    }

    @Test
    void developmentForwardedHeaderPolicyFailsClosedOnMissingOrOverriddenValues() {
        MockEnvironment safe = new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "none");
        MockEnvironment overridden = new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "native");

        assertThat(UserAuthEnvironment.hasSafeDevelopmentForwardHeaderPolicy(safe)).isTrue();
        assertThat(UserAuthEnvironment.hasSafeDevelopmentForwardHeaderPolicy(overridden)).isFalse();
        assertThat(UserAuthEnvironment.hasSafeDevelopmentForwardHeaderPolicy(new MockEnvironment())).isFalse();
    }
}
