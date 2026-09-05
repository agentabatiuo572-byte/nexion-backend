package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.mock.env.MockEnvironment;

class SecurityConfigCorsTest {
    private final MockEnvironment development = new MockEnvironment();

    @Test
    void permitsBothLocalH5HostAliasesForDirectDevelopmentRequests() {
        development.setActiveProfiles("dev");
        CorsConfiguration cors = configuration(new SecurityConfig(null, null, development));

        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
        assertThat(cors.checkOrigin("http://[::1]:5173")).isEqualTo("http://[::1]:5173");
    }

    @Test
    void rejectsUntrustedOriginWhileSupportingCredentialedMutationPreflight() {
        development.setActiveProfiles("dev");
        CorsConfiguration cors = configuration(new SecurityConfig(null, null, development));

        assertThat(cors.checkOrigin("https://attacker.example")).isNull();
        assertThat(cors.checkOrigin("http://localhost:4444")).isNull();
        assertThat(cors.checkOrigin("http://192.168.8.20:5173")).isNull();
        assertThat(cors.checkHttpMethod(HttpMethod.POST)).isNotNull();
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getAllowedHeaders()).contains("*");
    }

    @Test
    void productionRejectsPrivateNetworksAndAllowsOnlyConfiguredHttpsOrigins() {
        MockEnvironment production = new MockEnvironment()
                .withProperty("nexion.cors.allowed-origins", "https://ops.nexgrid.example,http://insecure.example");
        production.setActiveProfiles("prod");
        CorsConfiguration cors = configuration(new SecurityConfig(null, null, production));

        assertThat(cors.checkOrigin("http://192.168.8.20:3002")).isNull();
        assertThat(cors.checkOrigin("http://localhost:5173")).isNull();
        assertThat(cors.checkOrigin("http://insecure.example")).isNull();
        assertThat(cors.checkOrigin("https://ops.nexgrid.example")).isEqualTo("https://ops.nexgrid.example");
    }

    private CorsConfiguration configuration(SecurityConfig config) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/app/wallet/sandbox/topups");
        CorsConfiguration cors = config.corsConfigurationSource().getCorsConfiguration(request);
        assertThat(cors).isNotNull();
        return cors;
    }
}
