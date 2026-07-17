package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityConfigErrorResponseTest {
    @Test
    void unauthenticatedAndForbiddenResponsesAreStableJson() throws Exception {
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        SecurityConfig.writeJsonError(unauthorized, 401, "AUTH_REQUIRED");

        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getContentType()).startsWith("application/json");
        assertThat(unauthorized.getContentAsString()).isEqualTo(
                "{\"code\":401,\"message\":\"AUTH_REQUIRED\",\"data\":null}");

        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        SecurityConfig.writeJsonError(forbidden, 403, "ACCESS_DENIED");
        assertThat(forbidden.getContentAsString()).contains("\"code\":403", "\"message\":\"ACCESS_DENIED\"");
    }
}
