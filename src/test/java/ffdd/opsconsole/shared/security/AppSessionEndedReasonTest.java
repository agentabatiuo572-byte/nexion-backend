package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class AppSessionEndedReasonTest {
    private final JwtTokenProvider tokens = new JwtTokenProvider(new JwtProperties());
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, sessions, users,
            new MockEnvironment().withProperty("spring.profiles.active", "dev"), new GatewaySecurityProperties(),
            mock(AdminSessionRegistry.class), mock(AdminPermissionCache.class), mock(ImpersonationSessionVerifier.class),
            mock(PlatformConfigFacade.class), mock(UserAccountBlocklistVerifier.class), mock(UserSessionRevocationService.class));

    AppSessionEndedReasonTest() {
        UserEntity user = new UserEntity();
        user.setId(42L); user.setStatus("ACTIVE"); user.setIsDeleted(0); user.setSandbox(0);
        user.setCountryCode("+84"); user.setPhone("987654321");
        when(users.selectById(42L)).thenReturn(user);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void replacedAppProducesStructured401AndSocketReason() throws Exception {
        var session = session("APP"); session.setSessionEndedReason("APP_REPLACED");
        when(sessions.findOwnedSession("old-session", 42L)).thenReturn(session);
        var response = rejectedResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"sessionEndedReason\":\"APP_REPLACED\"");
        assertThatThrownBy(() -> filter.authenticateSocketToken(token(UserAuthEnvironment.PRODUCTION)))
                .hasMessage("APP_REPLACED");
    }

    @Test void h5ExpiryAndIdleExpiryStayOrdinaryExpiration() throws Exception {
        var session = session("H5"); session.setExpiresAt(now().minusSeconds(1));
        when(sessions.findOwnedSession("old-session", 42L)).thenReturn(session);
        assertThat(rejectedResponse().getContentAsString()).contains("SESSION_EXPIRED").doesNotContain("APP_REPLACED");
        session.setExpiresAt(now().plusDays(1)); session.setLastActiveAt(now().minusDays(31));
        assertThat(rejectedResponse().getContentAsString()).contains("SESSION_EXPIRED");
    }

    @Test void unknownLegacyContextRequiresReauthentication() throws Exception {
        when(sessions.findOwnedSession("old-session", 42L)).thenReturn(session("UNKNOWN"));
        assertThat(rejectedResponse().getContentAsString()).contains("REAUTH_REQUIRED");
    }

    @Test void cannotReadAnotherUsersSessionReason() throws Exception {
        var other = session("APP"); other.setUserId(43L); other.setSessionEndedReason("APP_REPLACED");
        when(sessions.findOwnedSession("old-session", 42L)).thenReturn(other);
        assertThat(rejectedResponse().getContentAsString()).contains("\"data\":null");
        verify(sessions).findOwnedSession("old-session", 42L);
    }

    @Test void wrongAudienceDoesNotQuerySessionReason() throws Exception {
        var request = request(token(UserAuthEnvironment.SANDBOX));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        verifyNoInteractions(sessions);
        assertThat(request.getAttribute(JwtAuthenticationFilter.SESSION_ENDED_REASON)).isNull();
    }

    @Test void staleBearerDoesNotPreventPublicRecoveryEndpoint() throws Exception {
        var session = session("APP"); session.setSessionEndedReason("APP_REPLACED");
        when(sessions.findOwnedSession("old-session", 42L)).thenReturn(session);
        var request = request(token(UserAuthEnvironment.PRODUCTION));
        request.setRequestURI("/auth/users/login/attempts/example/recover");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> res.getWriter().write("public-handler"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("public-handler");
    }

    private MockHttpServletResponse rejectedResponse() throws Exception {
        var request = request(token(UserAuthEnvironment.PRODUCTION));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> SecurityConfig.writeAuthenticationRequired(request, response));
        return response;
    }
    private MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest("GET", "/api/app/profile");
        request.addHeader("Authorization", "Bearer " + token); return request;
    }
    private String token(UserAuthEnvironment audience) {
        return tokens.createUserToken(42L, "987654321", List.of(), "old-session", Duration.ofHours(1), audience);
    }
    private UserSessionEntity session(String group) {
        var session = new UserSessionEntity(); session.setUserId(42L); session.setSessionGroup(group);
        session.setExpiresAt(now().plusDays(1)); session.setLastActiveAt(now()); return session;
    }
    private LocalDateTime now() { return LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE); }
}
