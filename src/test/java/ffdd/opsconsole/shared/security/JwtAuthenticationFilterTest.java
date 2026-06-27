package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.util.List;
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
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            tokenProvider,
            authSessionMapper,
            gatewayProperties,
            adminSessionRegistry);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesAdminTokenWhenRedisSessionIsActive() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        when(adminSessionRegistry.isSessionActive(1L, "admin-session-1")).thenReturn(true);
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

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }
}
