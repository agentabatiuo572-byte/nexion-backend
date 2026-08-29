package ffdd.opsconsole.auth.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.auth.application.AppUserAuthService;
import ffdd.opsconsole.auth.application.AppUserOAuthService;
import ffdd.opsconsole.auth.application.AppUserPasswordResetService;
import ffdd.opsconsole.auth.application.AppUserRegistrationService;
import ffdd.opsconsole.auth.application.AppUserRefreshCookieService;
import ffdd.opsconsole.auth.application.OAuthSandboxChallengeService;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeRequest;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeResponse;
import ffdd.opsconsole.auth.dto.UserOAuthSandboxChallengeRequest;
import ffdd.opsconsole.auth.dto.UserOAuthSandboxChallengeResponse;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.ServletException;

@WebMvcTest(AppUserAuthController.class)
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {AppUserAuthController.class, SecurityConfig.class})
class AppUserAuthControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppUserAuthService authService;

    @MockBean
    private AppUserRegistrationService registrationService;

    @MockBean
    private AppUserPasswordResetService passwordResetService;

    @MockBean
    private AppUserOAuthService oauthService;

    @MockBean
    private OAuthSandboxChallengeService oauthSandboxChallengeService;

    @MockBean
    private AppUserRefreshCookieService refreshCookieService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @BeforeEach
    void continueAuthenticationFilters() throws Exception {
        when(refreshCookieService.issueOAuth(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(adminRbacAuthorizationFilter).doFilter(any(), any(), any());
    }

    @Test
    void anonymousOAuthExchangeReachesService() throws Exception {
        UserOAuthExchangeRequest request = new UserOAuthExchangeRequest(
                "PASSKEY", "SANDBOX_MOCK", null, "Passkey User",
                "OAUTH-11111111111111111111111111111111");
        when(oauthService.exchange(eq(request), eq("127.0.0.1"),
                eq("http://127.0.0.1:5173"))).thenReturn(ApiResult.ok(
                new UserOAuthExchangeResponse(
                        "access", "Bearer",
                        new UserLoginResponse.UserSession(301L, "+1", "900123456789", "Passkey User"),
                        "refresh", "mock", true)));

        mockMvc.perform(post("/auth/users/oauth/exchange")
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        })
                        .header("Origin", "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"PASSKEY\",\"mode\":\"SANDBOX_MOCK\","
                                + "\"challengeNo\":\"OAUTH-11111111111111111111111111111111\","
                                + "\"displayName\":\"Passkey User\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").value("access"));

        verify(oauthService).exchange(eq(request), eq("127.0.0.1"),
                eq("http://127.0.0.1:5173"));
    }

    @Test
    void emptyOAuthExchangeBodyReturnsValidationFailure() throws Exception {
        when(oauthService.exchange(eq(null), eq("127.0.0.1"), eq(null)))
                .thenReturn(ApiResult.fail(422, "OAUTH_REQUEST_INVALID"));

        mockMvc.perform(post("/auth/users/oauth/exchange")
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("OAUTH_REQUEST_INVALID"));

        verify(oauthService).exchange(eq(null), eq("127.0.0.1"), eq(null));
    }

    @Test
    void developmentRuntimeDoesNotRegisterTheRetiredOAuthSandboxChallenge() throws Exception {
        mockMvc.perform(post("/auth/users/oauth/sandbox/challenge")
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        })
                        .header("Origin", "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"TELEGRAM\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(oauthSandboxChallengeService);
    }

    @Test
    void anonymousOnlyExactExchangeRouteIsPublic() throws Exception {
        mockMvc.perform(post("/auth/users/oauth/exchange/other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/auth/users/password-reset/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void conflictingRefreshCredentialsClearTheBrowserCookieBeforeReturningFailure() throws Exception {
        when(refreshCookieService.cookieMode(any())).thenReturn(true);
        when(refreshCookieService.resolve(any(), any()))
                .thenThrow(new BizException(401, "USER_REFRESH_CREDENTIAL_CONFLICT"));

        assertThrows(ServletException.class, () -> mockMvc.perform(post("/auth/users/refresh")
                .header(AppUserRefreshCookieService.MODE_HEADER,
                        AppUserRefreshCookieService.COOKIE_MODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"conflicting-body-token\"}")));

        verify(refreshCookieService).clear(any());
    }
}
