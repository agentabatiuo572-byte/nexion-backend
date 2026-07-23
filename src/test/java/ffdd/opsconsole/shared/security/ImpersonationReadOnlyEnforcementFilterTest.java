package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ImpersonationReadOnlyEnforcementFilterTest {
    private final ImpersonationReadOnlyEnforcementFilter filter = new ImpersonationReadOnlyEnforcementFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsEveryWriteForImpersonationIdentity() throws Exception {
        authenticateImpersonation();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/impersonation/view");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("IMPERSONATION_READ_ONLY");
    }

    @Test
    void allowsOnlyTheDedicatedReadonlyView() throws Exception {
        authenticateImpersonation();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/impersonation/view");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
    }

    private void authenticateImpersonation() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "7", null, List.of(new SimpleGrantedAuthority("impersonate_readonly")));
        authentication.setDetails(Map.of("subjectType", "IMPERSONATION", "sessionId", "IMP-READONLY-1"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
