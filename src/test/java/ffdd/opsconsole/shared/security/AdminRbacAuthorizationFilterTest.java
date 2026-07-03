package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.audit.AuditLogService;
import jakarta.servlet.FilterChain;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminRbacAuthorizationFilterTest {
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminRbacAuthorizationFilter filter = new AdminRbacAuthorizationFilter(auditLogService);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void permitsLoginWithoutAuthentication() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request("POST", "/api/admin/auth/login"), new MockHttpServletResponse(), mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void skipsOptionsPreflightWithoutAuthentication() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request("OPTIONS", "/api/admin/users/profiles"), new MockHttpServletResponse(), mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsUnauthenticatedAdminPath() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/admin/users/profiles"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("ADMIN_AUTH_REQUIRED");
    }

    @Test
    void permitsDomainReadWhenReadAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("PERM_USER_READ");

        filter.doFilter(request("GET", "/api/admin/users/profiles"), new MockHttpServletResponse(), mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsDomainWriteWhenOnlyReadAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("PERM_USER_READ");

        filter.doFilter(request("POST", "/api/admin/users/profiles"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_PERMISSION_DENIED");
        verify(auditLogService).record(argThat(request ->
                "A1_RBAC_ACCESS_DENIED".equals(request.getAction())
                        && "ADMIN_RBAC".equals(request.getResourceType())
                        && "/api/admin/users/profiles".equals(request.getResourceId())
                        && "DENIED".equals(request.getResult())));
    }

    @Test
    void rejectsPlatformEventMutationWhenOnlyAuditExportAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("PERM_AUDIT_READ", "PERM_AUDIT_EXPORT");

        filter.doFilter(request("POST", "/api/admin/platform/events/domain-extension-batches"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_PERMISSION_DENIED");
    }

    @Test
    void permitsPlatformEventMutationWhenSystemWriteAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("PERM_AUDIT_READ", "PERM_SYSTEM_WRITE");

        filter.doFilter(
                request("POST", "/api/admin/platform/events/domain-extension-batches"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsA1RoleMutationWithLegacySeatPermissionOnly() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("PERM_SYSTEM_READ", "PERM_SUPPORT_SEAT_WRITE");

        filter.doFilter(
                request("PATCH", "/api/admin/platform/accounts/6/role"),
                response,
                mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void permitsA1RoleMutationWithSystemWriteAuthority() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE");

        filter.doFilter(
                request("PATCH", "/api/admin/platform/accounts/6/role"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsSupportSeatRoleMutationWithSystemReadOnly() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("PERM_SYSTEM_READ");

        filter.doFilter(request("PATCH", "/api/admin/platform/accounts/6/role"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_PERMISSION_DENIED");
    }

    @Test
    void permitsEmergencyControlReadWhenEmergencyReadAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("PERM_EMERGENCY_READ");

        filter.doFilter(
                request("GET", "/api/admin/emergency-control/geo-block"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void permitsEmergencyControlWriteWhenEmergencyWriteAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("PERM_EMERGENCY_WRITE");

        filter.doFilter(
                request("POST", "/api/admin/emergency-control/geo-block/emergency-blocks"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void permitsMediaReadForAnyAuthenticatedAdmin() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate();

        filter.doFilter(
                request("GET", "/api/admin/media/uploads/asset-1/preview-url"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void permitsMediaWriteWhenAnyAdminWriteAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("PERM_CONTENT_WRITE");

        filter.doFilter(request("POST", "/api/admin/media/uploads"), new MockHttpServletResponse(), mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsAdminPathWithoutRbacRule() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("PERM_SYSTEM_READ");

        filter.doFilter(request("GET", "/api/admin/unmapped/resource"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_RBAC_RULE_MISSING");
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private FilterChain mark(AtomicBoolean invoked) {
        return (request, response) -> invoked.set(true);
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin-1",
                null,
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()));
    }
}
