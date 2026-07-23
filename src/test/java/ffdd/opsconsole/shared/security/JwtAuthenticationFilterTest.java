package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {
    private final JwtProperties jwtProperties = new JwtProperties();
    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(jwtProperties);
    private final AuthSessionMapper authSessionMapper = mock(AuthSessionMapper.class);
    private final GatewaySecurityProperties gatewayProperties = new GatewaySecurityProperties();
    private final AdminSessionRegistry adminSessionRegistry = mock(AdminSessionRegistry.class);
    private final AdminPermissionCache permissionCache = mock(AdminPermissionCache.class);
    private final ImpersonationSessionVerifier impersonationSessionVerifier = mock(ImpersonationSessionVerifier.class);
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            tokenProvider,
            authSessionMapper,
            gatewayProperties,
            adminSessionRegistry,
            permissionCache,
            impersonationSessionVerifier,
            configFacade);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesAdminTokenWhenRedisSessionIsActive() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        when(adminSessionRegistry.isSessionActive(1L, "admin-session-1")).thenReturn(true);
        when(permissionCache.getPermissionCodes(1L)).thenReturn(Set.of("PERM_SYSTEM_READ"));
        MockHttpServletRequest request = requestWithBearer(tokenProvider.createToken(
                1L,
                "ADMIN",
                "superadmin",
                List.of("PERM_SYSTEM_READ"),
                "admin-session-1"));

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("1");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("PERM_SYSTEM_READ");
    }

    @Test
    void doesNotAuthenticateAdminTokenWhenRedisSessionIsMissing() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        when(adminSessionRegistry.isSessionActive(1L, "admin-session-1")).thenReturn(false);
        MockHttpServletRequest request = requestWithBearer(tokenProvider.createToken(
                1L,
                "ADMIN",
                "superadmin",
                List.of("PERM_SYSTEM_READ"),
                "admin-session-1"));

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doesNotAuthenticateAdminTokenWithoutSessionIdClaim() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MockHttpServletRequest request = requestWithBearer(tokenProvider.createToken(
                1L,
                "ADMIN",
                "superadmin",
                List.of("PERM_SYSTEM_READ")));

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(adminSessionRegistry);
    }

    @Test
    void trustedGatewayHeadersPreserveUserSubjectTypeForAppEndpoints() throws Exception {
        gatewayProperties.setHeaderAuthenticationEnabled(true);
        gatewayProperties.setInternalSecret("test-gateway-secret-with-32-characters");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/content/app/conversations/CV-1/receipts/read");
        request.addHeader(AuthHeaders.GATEWAY_SECRET, gatewayProperties.getInternalSecret());
        request.addHeader(AuthHeaders.SUBJECT_ID, "1001");
        request.addHeader(AuthHeaders.SUBJECT_TYPE, "user");
        request.addHeader(AuthHeaders.USERNAME, "customer-1001");

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("1001");
        assertThat(authentication.getDetails()).isEqualTo(Map.of(
                "subjectType", "USER",
                "username", "customer-1001"));
    }

    @Test
    void trustedGatewayHeadersWithoutSubjectTypeDoNotAuthenticate() throws Exception {
        gatewayProperties.setHeaderAuthenticationEnabled(true);
        gatewayProperties.setInternalSecret("test-gateway-secret-with-32-characters");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/content/app/test");
        request.addHeader(AuthHeaders.GATEWAY_SECRET, gatewayProperties.getInternalSecret());
        request.addHeader(AuthHeaders.SUBJECT_ID, "1001");

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authenticatesActiveImpersonationWithReadOnlyClaim() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        when(impersonationSessionVerifier.isActive(7L, "IMP-READONLY-1")).thenReturn(true);
        MockHttpServletRequest request = requestWithBearer(tokenProvider.createImpersonationToken(
                7L, "U00000007", "IMP-READONLY-1", 15));

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("impersonate_readonly");
        assertThat(authentication.getDetails()).isEqualTo(Map.of(
                "subjectType", "IMPERSONATION",
                "username", "U00000007",
                "sessionId", "IMP-READONLY-1"));
    }

    @Test
    void userSessionMustBeWithinConfiguredIdleWindowAndIsTouchedAtomically() throws Exception {
        when(configFacade.activeValue("auth.session.idle_ttl_days")).thenReturn(java.util.Optional.of("14"));
        when(authSessionMapper.touchActiveUserSession("user-session-1", 42L, 14)).thenReturn(1);
        MockHttpServletRequest request = requestWithBearer(tokenProvider.createToken(
                42L, "USER", "user-42", List.of(), "user-session-1"));

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        org.mockito.Mockito.verify(authSessionMapper).touchActiveUserSession("user-session-1", 42L, 14);
    }

    @Test
    void failsClosedWithServiceUnavailableWhenAdminSessionStoreCannotBeRead() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        when(adminSessionRegistry.isSessionActive(1L, "admin-session-1"))
                .thenThrow(new IllegalStateException("redis down"));
        MockHttpServletRequest request = requestWithBearer(tokenProvider.createToken(
                1L,
                "ADMIN",
                "superadmin",
                List.of("PERM_SYSTEM_READ"),
                "admin-session-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("ADMIN_SESSION_STORE_UNAVAILABLE");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void gatewayHeaderAuthenticationIsDisabledByDefault() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trial/eligibility");
        request.addHeader(AuthHeaders.GATEWAY_SECRET, "nexion-local-gateway-secret");
        request.addHeader(AuthHeaders.SUBJECT_ID, "52");
        request.addHeader(AuthHeaders.SUBJECT_TYPE, "USER");

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void gatewayHeaderAuthenticationRejectsUntrustedPeer() throws Exception {
        gatewayProperties.setHeaderAuthenticationEnabled(true);
        gatewayProperties.setInternalSecret("test-gateway-secret-with-32-characters");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trial/eligibility");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader(AuthHeaders.GATEWAY_SECRET, gatewayProperties.getInternalSecret());
        request.addHeader(AuthHeaders.SUBJECT_ID, "52");
        request.addHeader(AuthHeaders.SUBJECT_TYPE, "USER");

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }
}
