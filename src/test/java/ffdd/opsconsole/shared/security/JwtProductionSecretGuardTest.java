package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtProductionSecretGuardTest {
    @Test
    void productionRejectsTheCheckedInDevelopmentSecret() {
        assertThatThrownBy(() -> new JwtProductionSecretGuard(new JwtProperties(), new MockEnvironment()).afterPropertiesSet())
                .hasMessage("NEXION_JWT_SECRET_REQUIRED_IN_PRODUCTION");
    }

    @Test
    void testFixtureMayExplicitlyUseDevelopmentSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        assertThatCode(() -> new JwtProductionSecretGuard(new JwtProperties(), environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void productionAcceptsAnExplicitNonDefaultSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("a-production-only-secret-that-exceeds-thirty-two-characters");
        assertThatCode(() -> new JwtProductionSecretGuard(properties, new MockEnvironment()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
