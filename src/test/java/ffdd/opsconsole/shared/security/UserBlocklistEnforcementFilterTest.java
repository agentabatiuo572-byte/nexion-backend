package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class UserBlocklistEnforcementFilterTest {
    private final UserAccountBlocklistVerifier verifier = mock(UserAccountBlocklistVerifier.class);
    private final UserBlocklistEnforcementFilter filter = new UserBlocklistEnforcementFilter(verifier);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksEveryAuthenticatedUserRequestWhenC2BlocklistIsActive() throws Exception {
        authenticate("USER");
        when(verifier.isBlocked(52L)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/commerce/app/orders"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCOUNT_BLOCKLISTED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doesNotApplyUserBlocklistToAdminOrImpersonationIdentity() throws Exception {
        authenticate("ADMIN");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/admin/users"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(verifier);
    }

    private void authenticate(String subjectType) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("52", null, java.util.List.of());
        auth.setDetails(Map.of("subjectType", subjectType));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
