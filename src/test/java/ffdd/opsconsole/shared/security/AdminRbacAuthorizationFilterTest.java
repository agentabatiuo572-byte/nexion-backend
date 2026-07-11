package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
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
    private final AdminAccountStateMapper accountStateMapper = mock(AdminAccountStateMapper.class);
    private final AdminRbacAuthorizationFilter filter = new AdminRbacAuthorizationFilter(auditLogService, accountStateMapper);

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
        authenticate("user_c1_read");

        filter.doFilter(request("GET", "/api/admin/users/profiles"), new MockHttpServletResponse(), mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void permitsOverviewRoleOnDashboardAndBDomainTreasuryRoutes() throws Exception {
        AtomicBoolean dashboardInvoked = new AtomicBoolean(false);
        AtomicBoolean treasuryInvoked = new AtomicBoolean(false);
        authenticate("overview_b1_read");

        filter.doFilter(request("GET", "/api/admin/ops-dashboard/summary"),
                new MockHttpServletResponse(), mark(dashboardInvoked));
        filter.doFilter(request("GET", "/api/admin/treasury/b-domain"),
                new MockHttpServletResponse(), mark(treasuryInvoked));

        assertThat(dashboardInvoked).isTrue();
        assertThat(treasuryInvoked).isTrue();
    }

    @Test
    void overviewPermissionDoesNotOpenFinanceTreasuryRoutes() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("overview_b1_read");

        filter.doFilter(request("GET", "/api/admin/treasury/dual-ledger"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsDomainWriteWhenOnlyReadAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("user_c1_read");

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
        authenticate("platform_a2_read", "platform_a2_operation_approve");

        filter.doFilter(request("POST", "/api/admin/platform/events/domain-extension-batches"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_PERMISSION_DENIED");
    }

    @Test
    void permitsPlatformEventMutationWhenSystemWriteAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("platform_a4_write");

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
        authenticate("platform_a1_read", "PERM_SUPPORT_SEAT_WRITE");

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
        authenticate("platform_a1_read", "platform_a1_account_role_change");

        filter.doFilter(
                request("PATCH", "/api/admin/platform/accounts/6/role"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsBusinessApisWhenAdminMustChangePassword() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticateAs("4", "platform_a1_read", "platform_a1_write");
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setAdminId(4L);
        state.setCredentialDeliveryStatus("PASSWORD_CHANGE_REQUIRED");
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(state);

        filter.doFilter(request("GET", "/api/admin/platform/accounts/overview"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_PASSWORD_CHANGE_REQUIRED");
        verify(auditLogService).record(argThat(request ->
                "A1_RBAC_ACCESS_DENIED".equals(request.getAction())
                        && "ADMIN_PASSWORD_CHANGE_REQUIRED".equals(((java.util.Map<?, ?>) request.getDetail()).get("reason"))));
    }

    @Test
    void rejectsBusinessApisForLegacyCredentialDeliveryStates() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticateAs("4", "platform_a1_read", "platform_a1_write");
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setAdminId(4L);
        state.setCredentialDeliveryStatus("MAIL_DISPATCHED");
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(state);

        filter.doFilter(request("GET", "/api/admin/platform/accounts/overview"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    void permitsPasswordChangeEndpointsWhenAdminMustChangePassword() throws Exception {
        AtomicBoolean meInvoked = new AtomicBoolean(false);
        AtomicBoolean changeInvoked = new AtomicBoolean(false);
        authenticateAs("4", "platform_a1_read");
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setAdminId(4L);
        state.setCredentialDeliveryStatus("PASSWORD_CHANGE_REQUIRED");
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(state);

        filter.doFilter(request("GET", "/api/admin/auth/me"), new MockHttpServletResponse(), mark(meInvoked));
        filter.doFilter(request("POST", "/api/admin/auth/password/change"), new MockHttpServletResponse(), mark(changeInvoked));

        assertThat(meInvoked).isTrue();
        assertThat(changeInvoked).isTrue();
    }

    @Test
    void rejectsSupportSeatRoleMutationWithSystemReadOnly() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("platform_a1_read");

        filter.doFilter(request("PATCH", "/api/admin/platform/accounts/6/role"), response, mark(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_PERMISSION_DENIED");
    }

    @Test
    void permitsEmergencyControlReadWhenEmergencyReadAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("emergency_j2_read");

        filter.doFilter(
                request("GET", "/api/admin/emergency-control/geo-block"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void permitsEmergencyControlWriteWhenEmergencyWriteAuthorityIsPresent() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("emergency_j2_write");

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
        authenticate("content_i1_write");

        filter.doFilter(request("POST", "/api/admin/media/uploads"), new MockHttpServletResponse(), mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void permitsSupportReadForSupportCenterApis() throws Exception {
        AtomicBoolean ticketsInvoked = new AtomicBoolean(false);
        AtomicBoolean agentsInvoked = new AtomicBoolean(false);
        authenticate("service_m2_read", "service_m1_read");

        filter.doFilter(request("GET", "/api/admin/content/tickets"), new MockHttpServletResponse(), mark(ticketsInvoked));
        filter.doFilter(request("GET", "/api/admin/content/support-agents/page"), new MockHttpServletResponse(), mark(agentsInvoked));

        assertThat(ticketsInvoked).isTrue();
        assertThat(agentsInvoked).isTrue();
    }

    @Test
    void permitsSupportWriteForSupportCenterApis() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        authenticate("service_m1_write");

        filter.doFilter(
                request("PATCH", "/api/admin/content/support-agents/6/seat-assignment"),
                new MockHttpServletResponse(),
                mark(invoked));

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsSupportAuthorityForNonSupportContentApis() throws Exception {
        AtomicBoolean novaInvoked = new AtomicBoolean(false);
        AtomicBoolean copyAbInvoked = new AtomicBoolean(false);
        MockHttpServletResponse novaResponse = new MockHttpServletResponse();
        MockHttpServletResponse copyAbResponse = new MockHttpServletResponse();
        authenticate("service_m1_read");

        filter.doFilter(request("GET", "/api/admin/content/nova/overview"), novaResponse, mark(novaInvoked));
        filter.doFilter(request("GET", "/api/admin/content/copy-ab/overview"), copyAbResponse, mark(copyAbInvoked));

        assertThat(novaInvoked).isFalse();
        assertThat(novaResponse.getStatus()).isEqualTo(403);
        assertThat(novaResponse.getContentAsString()).contains("ADMIN_PERMISSION_DENIED");
        assertThat(copyAbInvoked).isFalse();
        assertThat(copyAbResponse.getStatus()).isEqualTo(403);
        assertThat(copyAbResponse.getContentAsString()).contains("ADMIN_PERMISSION_DENIED");
    }

    @Test
    void permitsSupportReadForWorkbenchLookupApis() throws Exception {
        AtomicBoolean usersInvoked = new AtomicBoolean(false);
        AtomicBoolean skusInvoked = new AtomicBoolean(false);
        authenticate("service_m1_read");

        filter.doFilter(request("GET", "/api/admin/content/support-workbench/users"), new MockHttpServletResponse(), mark(usersInvoked));
        filter.doFilter(
                request("GET", "/api/admin/content/support-workbench/skus"),
                new MockHttpServletResponse(),
                mark(skusInvoked));

        assertThat(usersInvoked).isTrue();
        assertThat(skusInvoked).isTrue();
    }

    @Test
    void rejectsSupportAuthorityForNonWorkbenchUserAndDeviceApis() throws Exception {
        AtomicBoolean userProfilesInvoked = new AtomicBoolean(false);
        AtomicBoolean user360Invoked = new AtomicBoolean(false);
        AtomicBoolean skuReadInvoked = new AtomicBoolean(false);
        AtomicBoolean gateReadInvoked = new AtomicBoolean(false);
        AtomicBoolean reviewReadInvoked = new AtomicBoolean(false);
        AtomicBoolean platformInvoked = new AtomicBoolean(false);
        AtomicBoolean treasuryInvoked = new AtomicBoolean(false);
        MockHttpServletResponse userProfilesResponse = new MockHttpServletResponse();
        MockHttpServletResponse user360Response = new MockHttpServletResponse();
        MockHttpServletResponse skuReadResponse = new MockHttpServletResponse();
        MockHttpServletResponse gateReadResponse = new MockHttpServletResponse();
        MockHttpServletResponse reviewReadResponse = new MockHttpServletResponse();
        MockHttpServletResponse platformResponse = new MockHttpServletResponse();
        MockHttpServletResponse treasuryResponse = new MockHttpServletResponse();
        authenticate("service_m1_read");

        filter.doFilter(request("GET", "/api/admin/users/profiles"), userProfilesResponse, mark(userProfilesInvoked));
        filter.doFilter(request("GET", "/api/admin/users/profiles/6/360"), user360Response, mark(user360Invoked));
        filter.doFilter(request("GET", "/api/admin/devices/skus"), skuReadResponse, mark(skuReadInvoked));
        filter.doFilter(request("GET", "/api/admin/devices/e1/generation-gates"), gateReadResponse, mark(gateReadInvoked));
        filter.doFilter(request("GET", "/api/admin/devices/reviews"), reviewReadResponse, mark(reviewReadInvoked));
        filter.doFilter(request("GET", "/api/admin/platform/accounts/overview"), platformResponse, mark(platformInvoked));
        filter.doFilter(request("GET", "/api/admin/treasury/overview"), treasuryResponse, mark(treasuryInvoked));

        assertThat(userProfilesInvoked).isFalse();
        assertThat(userProfilesResponse.getStatus()).isEqualTo(403);
        assertThat(user360Invoked).isFalse();
        assertThat(user360Response.getStatus()).isEqualTo(403);
        assertThat(skuReadInvoked).isFalse();
        assertThat(skuReadResponse.getStatus()).isEqualTo(403);
        assertThat(gateReadInvoked).isFalse();
        assertThat(gateReadResponse.getStatus()).isEqualTo(403);
        assertThat(reviewReadInvoked).isFalse();
        assertThat(reviewReadResponse.getStatus()).isEqualTo(403);
        assertThat(platformInvoked).isFalse();
        assertThat(platformResponse.getStatus()).isEqualTo(403);
        assertThat(treasuryInvoked).isFalse();
        assertThat(treasuryResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsAdminPathWithoutRbacRule() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate("platform_a1_read");

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
        authenticateAs("admin-1", authorities);
    }

    private void authenticateAs(String principal, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()));
    }
}
