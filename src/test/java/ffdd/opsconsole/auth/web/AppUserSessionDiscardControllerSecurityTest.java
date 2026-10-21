package ffdd.opsconsole.auth.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ffdd.opsconsole.auth.application.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AppUserAuthController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class, JwtProperties.class,
        GatewaySecurityProperties.class, GlobalExceptionHandler.class})
@ContextConfiguration(classes = AppUserAuthController.class)
class AppUserSessionDiscardControllerSecurityTest {
    private static final String PATH = "/auth/users/session/discard";
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockBean AppUserAuthService auth;
    @MockBean AppUserRegistrationService registration;
    @MockBean AppUserPasswordResetService passwordReset;
    @MockBean AppUserOAuthService oauth;
    @MockBean AppUserRefreshCookieService cookies;
    @MockBean AuthSessionMapper sessions;
    @MockBean UserOpsMapper users;
    @MockBean AdminSessionRegistry adminSessions;
    @MockBean AdminPermissionCache permissions;
    @MockBean ImpersonationSessionVerifier impersonation;
    @MockBean PlatformConfigFacade config;
    @MockBean UserAccountBlocklistVerifier blocklist;
    @MockBean UserSessionRevocationService revocations;
    @MockBean AdminRbacAuthorizationFilter rbac;
    @MockBean AuditLogService audit;

    @BeforeEach void setup() throws Exception {
        var user = new UserEntity();
        user.setId(42L); user.setStatus("ACTIVE"); user.setIsDeleted(0); user.setSandbox(0);
        user.setCountryCode("+84"); user.setPhone("901234567");
        when(users.selectById(42L)).thenReturn(user);
        when(config.activeValue(anyString())).thenReturn(java.util.Optional.empty());
        when(sessions.touchActiveUserSession("snapshot-a", 42L, 30)).thenReturn(1);
        doAnswer(call -> {
            call.getArgument(2, jakarta.servlet.FilterChain.class).doFilter(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(rbac).doFilter(any(), any(), any());
    }

    @Test void originalBearerWithNewerCookieDiscardsOnlyOriginalPrincipalWithoutCookieOutput() throws Exception {
        when(auth.discardSession(any())).thenReturn(ApiResult.ok(Map.of("revoked", true)));
        mvc.perform(post(PATH).header("Authorization", "Bearer " + validToken())
                .header(AppUserRefreshCookieService.MODE_HEADER, "cookie")
                .cookie(new Cookie(AppUserRefreshCookieService.COOKIE_NAME, "newer-b-refresh")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.revoked").value(true))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(auth).discardSession(argThat(principal -> principal.isAuthenticated()
                && "42".equals(principal.getName())
                && principal.getDetails() instanceof Map<?, ?> details
                && "USER".equals(details.get("subjectType")) && "snapshot-a".equals(details.get("sessionId"))));
        verifyNoInteractions(cookies);
    }

    @Test void discardServiceFailureNeverClearsAmbientNewerCookie() throws Exception {
        when(auth.discardSession(any())).thenThrow(new BizException(503, "USER_SESSION_UNAVAILABLE"));
        mvc.perform(post(PATH).header("Authorization", "Bearer " + validToken())
                .cookie(new Cookie(AppUserRefreshCookieService.COOKIE_NAME, "newer-b-refresh")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(cookies);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "wrong-signature", "wrong-audience", "replaced"})
    void missingInvalidOrEndedBearerCannotFallBackToNewerCookie(String scenario) throws Exception {
        var request = post(PATH).cookie(new Cookie(AppUserRefreshCookieService.COOKIE_NAME, "newer-b-refresh"));
        String token = validToken();
        if ("malformed".equals(scenario)) token = "invalid-token";
        if ("wrong-signature".equals(scenario)) {
            var properties = new JwtProperties(); properties.setSecret("different-test-signature-key-that-is-long-enough");
            token = new JwtTokenProvider(properties).createUserToken(42L, "fixture", List.of(), "snapshot-a",
                    Duration.ofMinutes(5), UserAuthEnvironment.PRODUCTION);
        }
        if ("wrong-audience".equals(scenario)) token = tokens.createUserToken(42L, "fixture", List.of(), "snapshot-a",
                Duration.ofMinutes(5), UserAuthEnvironment.SANDBOX);
        if ("replaced".equals(scenario)) when(sessions.touchActiveUserSession("snapshot-a", 42L, 30)).thenReturn(0);
        if (!"missing".equals(scenario)) request.header("Authorization", "Bearer " + token);
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(auth, cookies);
    }

    @Test void authenticatedContextWithoutExplicitBearerStillCannotDiscard() throws Exception {
        var principal = new UsernamePasswordAuthenticationToken("42", null, List.of());
        principal.setDetails(Map.of("subjectType", "USER", "sessionId", "snapshot-a"));
        mvc.perform(post(PATH).with(authentication(principal))
                .cookie(new Cookie(AppUserRefreshCookieService.COOKIE_NAME, "newer-b-refresh")))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(auth, cookies);
    }

    private String validToken() {
        return tokens.createUserToken(42L, "fixture", List.of(), "snapshot-a", Duration.ofMinutes(5), UserAuthEnvironment.PRODUCTION);
    }
}
