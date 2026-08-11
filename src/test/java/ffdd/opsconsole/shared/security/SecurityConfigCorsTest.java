package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;

class SecurityConfigCorsTest {
    private final SecurityConfig config = new SecurityConfig(null, null);

    @Test
    void permitsBothLocalH5HostAliasesForDirectDevelopmentRequests() {
        CorsConfiguration cors = configuration();

        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
        assertThat(cors.checkOrigin("http://[::1]:5173")).isEqualTo("http://[::1]:5173");
    }

    @Test
    void rejectsUntrustedOriginWhileSupportingCredentialedMutationPreflight() {
        CorsConfiguration cors = configuration();

        assertThat(cors.checkOrigin("https://attacker.example")).isNull();
        assertThat(cors.checkHttpMethod(HttpMethod.POST)).isNotNull();
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getAllowedHeaders()).contains("*");
    }

    private CorsConfiguration configuration() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/app/wallet/sandbox/topups");
        CorsConfiguration cors = config.corsConfigurationSource().getCorsConfiguration(request);
        assertThat(cors).isNotNull();
        return cors;
    }
}
