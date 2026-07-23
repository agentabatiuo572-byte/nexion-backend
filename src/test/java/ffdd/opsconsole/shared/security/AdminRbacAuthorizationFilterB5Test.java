package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminRbacAuthorizationFilterB5Test {
    private final AdminRbacAuthorizationFilter filter = new AdminRbacAuthorizationFilter(
            mock(AuditLogService.class), mock(AdminAccountStateMapper.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void b5ReadDoesNotLeakToGenericRiskAuthorities() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(false);
        authenticate("overview_b5_read");
        filter.doFilter(request("GET", "/api/admin/risk/radar"),
                new MockHttpServletResponse(), mark(allowed));
        assertThat(allowed).isTrue();

        SecurityContextHolder.clearContext();
        AtomicBoolean denied = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("risk_k4_read");
        filter.doFilter(request("GET", "/api/admin/risk/radar"), response, mark(denied));
        assertThat(denied).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void b5WritesRequireAB5WriteAuthorityAtTheFilterBoundary() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(false);
        authenticate("overview_b5_threshold_write");
        filter.doFilter(request("PUT", "/api/admin/risk/bankrun-thresholds"),
                new MockHttpServletResponse(), mark(allowed));
        assertThat(allowed).isTrue();

        SecurityContextHolder.clearContext();
        AtomicBoolean denied = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("overview_b5_read");
        filter.doFilter(request("PUT", "/api/admin/risk/alert-subscription"), response, mark(denied));
        assertThat(denied).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private FilterChain mark(AtomicBoolean invoked) {
        return (request, response) -> invoked.set(true);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin-1", null, java.util.List.of(new SimpleGrantedAuthority(authority))));
    }
}
