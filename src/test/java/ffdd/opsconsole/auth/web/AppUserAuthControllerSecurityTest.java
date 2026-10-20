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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.auth.application.AppUserAuthService;
import ffdd.opsconsole.auth.application.AppSessionSurface;
import ffdd.opsconsole.auth.application.AppLoginContext;
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
import ffdd.opsconsole.auth.dto.UserOtpLoginChallengeResponse;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.ApiResultHttpStatusAdvice;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.ServletException;

@WebMvcTest(AppUserAuthController.class)
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {AppUserAuthController.class, SecurityConfig.class})
class AppUserAuthControllerSecurityTest {
    private static final String INSTALLATION = "security-test-installation";
    private static final String ATTEMPT = "security_test_attempt_001";
    private static final String RECOVERY = "r".repeat(64);
    private static final String LOGIN_BODY = "{\"countryCode\":\"+84\",\"phone\":\"901234567\","
            + "\"challengeNo\":\"challenge-001\",\"provider\":\"PASSKEY\"}";
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
        when(refreshCookieService.issue(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
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

    @ParameterizedTest
    @ValueSource(strings = { "/login", "/login/otp/verify", "/login/2fa", "/register", "/oauth/exchange" })
    void nativeMarkerReachesEverySessionIssuancePath(String path) throws Exception {
        mockMvc.perform(sessionPost("/auth/users" + path, true).content(LOGIN_BODY))
                .andExpect(status().isOk());

        switch (path) {
            case "/login" -> verify(authService).login(any(), eq("127.0.0.1"), eq(context(AppSessionSurface.APP, true, "PASSWORD", "+84:901234567")));
            case "/login/otp/verify" -> verify(authService).completeOtpLogin(
                    any(), eq("127.0.0.1"), eq(context(AppSessionSurface.APP, true, "OTP", "challenge-001")));
            case "/login/2fa" -> verify(authService).completeTwoFactorLogin(
                    any(), eq("127.0.0.1"), eq(context(AppSessionSurface.APP, true, "TWO_FACTOR", "challenge-001")));
            case "/register" -> verify(registrationService).register(
                    any(), eq("127.0.0.1"), eq(context(AppSessionSurface.APP, true, "REGISTRATION", "+84:901234567")));
            case "/oauth/exchange" -> verify(oauthService).exchange(
                    any(), eq("127.0.0.1"), eq(null), eq(context(AppSessionSurface.APP, true, "OAUTH", "PASSKEY:challenge-001")));
            default -> throw new AssertionError(path);
        }
    }

    @Test
    void cookieModeTakesPrecedenceAndMissingMarkerRequiresReauthentication() throws Exception {
        when(refreshCookieService.cookieMode(any())).thenAnswer(invocation ->
                "cookie".equals(invocation.getArgument(0, jakarta.servlet.http.HttpServletRequest.class)
                        .getHeader(AppUserRefreshCookieService.MODE_HEADER)));

        mockMvc.perform(sessionPost("/auth/users/login", false)
                        .header(AppUserRefreshCookieService.MODE_HEADER, "cookie")
                        .content(LOGIN_BODY))
                .andExpect(status().isOk());
        verify(authService).login(any(), eq("127.0.0.1"), eq(context(AppSessionSurface.H5, false, "PASSWORD", "+84:901234567")));

        ServletException rejected = assertThrows(ServletException.class, () -> mockMvc.perform(
                sessionPost("/auth/users/login", false).with(request -> {
                    request.removeHeader(AppSessionSurface.APP_HEADER); return request;
                }).content(LOGIN_BODY)));
        org.assertj.core.api.Assertions.assertThat(rejected.getCause()).hasMessage("SESSION_CONTEXT_REAUTH_REQUIRED");
        verify(authService, org.mockito.Mockito.times(1)).login(any(), any(), any(AppLoginContext.class));
    }

    @Test
    void anonymousRegistrationOtpVerificationReachesServiceWithoutIssuingASession() throws Exception {
        var request = new UserOtpLoginVerifyRequest(
                "+84", "901234567", "REG-0123456789abcdef0123456789abcdef", "123456");
        when(registrationService.verifyOtp(request))
                .thenReturn(ApiResult.ok(java.util.Map.of("status", "REGISTRATION_OTP_VERIFIED")));

        mockMvc.perform(post("/auth/users/register/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countryCode":"+84","phone":"901234567",
                                 "challengeNo":"REG-0123456789abcdef0123456789abcdef","code":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REGISTRATION_OTP_VERIFIED"));

        verify(registrationService).verifyOtp(request);
        verifyNoInteractions(authService, passwordResetService, refreshCookieService);
    }

    @Test
    void emptyRegistrationOtpVerificationBodyKeepsTheValidationErrorContract() throws Exception {
        when(registrationService.verifyOtp(null))
                .thenReturn(ApiResult.fail(422, "USER_REGISTRATION_OTP_INVALID"));
        MockMvc withStatusAdvice = MockMvcBuilders.standaloneSetup(new AppUserAuthController(
                        authService, registrationService, passwordResetService, oauthService, refreshCookieService))
                .setControllerAdvice(new ApiResultHttpStatusAdvice()).build();

        withStatusAdvice.perform(post("/auth/users/register/otp/verify").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("USER_REGISTRATION_OTP_INVALID"));

        verify(registrationService).verifyOtp(null);
        when(registrationService.verifyOtp(any()))
                .thenReturn(ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN"));
        withStatusAdvice.perform(post("/auth/users/register/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("USER_REGISTRATION_PROFILE_FORBIDDEN"));
        verifyNoInteractions(authService, passwordResetService, refreshCookieService);
    }

    @Test
    void anonymousOAuthExchangeReachesService() throws Exception {
        UserOAuthExchangeRequest request = new UserOAuthExchangeRequest(
                "PASSKEY", "SANDBOX_MOCK", null, "Passkey User",
                "OAUTH-11111111111111111111111111111111");
        when(oauthService.exchange(eq(request), eq("127.0.0.1"),
                eq("http://127.0.0.1:5173"), eq(context(AppSessionSurface.APP, false, "OAUTH", "PASSKEY:OAUTH-11111111111111111111111111111111")))).thenReturn(ApiResult.ok(
                new UserOAuthExchangeResponse(
                        "access", "Bearer",
                        new UserLoginResponse.UserSession(301L, "+86", "900123456789", "Passkey User"),
                        "refresh", "mock", true)));

        mockMvc.perform(sessionPost("/auth/users/oauth/exchange", false)
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
                eq("http://127.0.0.1:5173"), eq(context(AppSessionSurface.APP, false, "OAUTH", "PASSKEY:OAUTH-11111111111111111111111111111111")));
    }

    @Test
    void emptyOAuthExchangeBodyCannotCreateAnUnboundLoginAttempt() throws Exception {
        ServletException rejected = assertThrows(ServletException.class, () -> mockMvc.perform(
                sessionPost("/auth/users/oauth/exchange", false)));
        org.assertj.core.api.Assertions.assertThat(rejected.getCause()).hasMessage("LOGIN_ATTEMPT_CONTEXT_INVALID");
        verifyNoInteractions(oauthService);
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
    void anonymousOnlyExactPublicAuthRoutesAreReachable() throws Exception {
        mockMvc.perform(post("/auth/users/oauth/exchange/other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        when(passwordResetService.send(any(), eq("127.0.0.1")))
                .thenReturn(ApiResult.ok(new UserOtpLoginChallengeResponse(
                        "RESET-0123456789abcdef0123456789abcdef", 60, "****5678")));
        when(passwordResetService.complete(any(), eq("127.0.0.1")))
                .thenReturn(ApiResult.ok(java.util.Map.of(
                        "status", "PASSWORD_RESET", "revokedSessionCount", 2)));
        when(passwordResetService.verify(any()))
                .thenReturn(ApiResult.ok(java.util.Map.of("status", "PASSWORD_RESET_OTP_VERIFIED")));

        mockMvc.perform(post("/auth/users/password-reset/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\":\"+84\",\"phone\":\"901234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/auth/users/password-reset/otp/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\":\"+84\",\"phone\":\"901234567\","
                                + "\"challengeNo\":\"RESET-0123456789abcdef0123456789abcdef\","
                                + "\"code\":\"123456\",\"newPassword\":\"NewPassword2!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/auth/users/password-reset/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\":\"+84\",\"phone\":\"901234567\","
                                + "\"challengeNo\":\"RESET-0123456789abcdef0123456789abcdef\","
                                + "\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/auth/users/password-reset/otp/send/other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/auth/users/password-reset/otp/verify/other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void retiredCurrentPasswordResetRouteIsNotPubliclyReachable() throws Exception {
        mockMvc.perform(post("/auth/users/password-reset/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old\",\"newPassword\":\"new\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(authService, org.mockito.Mockito.never()).completePasswordReset(any(), any());
        verify(authService, org.mockito.Mockito.never()).completePasswordReset(any(), any(), any(AppLoginContext.class));
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

    @Test
    void supersededRotationDoesNotClearANewerCookie() throws Exception {
        when(refreshCookieService.cookieMode(any())).thenReturn(true);
        when(refreshCookieService.resolve(any(), any()))
                .thenReturn(new ffdd.opsconsole.auth.dto.UserRefreshRequest("older-cookie"));
        when(authService.refresh(any(), eq("a".repeat(64)), eq(true)))
                .thenReturn(ApiResult.fail(409, "USER_REFRESH_ROTATION_SUPERSEDED"));

        mockMvc.perform(post("/auth/users/refresh")
                        .header(AppUserRefreshCookieService.MODE_HEADER, AppUserRefreshCookieService.COOKIE_MODE)
                        .header(AppUserRefreshCookieService.ROTATION_KEY_HEADER, "a".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("USER_REFRESH_ROTATION_SUPERSEDED"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verify(refreshCookieService, org.mockito.Mockito.never()).clear(any());
    }

    @Test
    void nativeRefreshPassesRotationProofWithoutSelectingCookieMode() throws Exception {
        var credential = new ffdd.opsconsole.auth.dto.UserRefreshRequest("native-token");
        when(refreshCookieService.resolve(any(), any())).thenReturn(credential);
        when(authService.refresh(eq(credential), eq("a".repeat(64)), eq(false)))
                .thenReturn(ApiResult.fail(409, "USER_REFRESH_ROTATION_SUPERSEDED"));
        when(refreshCookieService.issue(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/auth/users/refresh")
                        .header(AppUserRefreshCookieService.ROTATION_KEY_HEADER, "a".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"native-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verify(authService).refresh(eq(credential), eq("a".repeat(64)), eq(false));
        verify(refreshCookieService, org.mockito.Mockito.never()).clear(any());
    }

    @Test
    void unsupportedNativeExecutionStillReachesTheAppLoginBoundary() throws Exception {
        mockMvc.perform(sessionPost("/auth/users/login", false).content(LOGIN_BODY)).andExpect(status().isOk());
        verify(authService).login(any(), eq("127.0.0.1"), eq(context(AppSessionSurface.APP, false, "PASSWORD", "+84:901234567")));
    }

    @Test
    void missingExecutionContextCannotIssueASession() throws Exception {
        ServletException rejected = assertThrows(ServletException.class, () -> mockMvc.perform(
                sessionPost("/auth/users/login", false).with(request -> {
                    request.removeHeader("X-NexGrid-Execution-Supported"); return request;
                }).content(LOGIN_BODY)));
        org.assertj.core.api.Assertions.assertThat(rejected.getCause()).hasMessage("LOGIN_EXECUTION_CONTEXT_INVALID");
        verifyNoInteractions(authService);
    }

    @Test
    void recoveryPassesTheOriginalAttemptAndProofToTheService() throws Exception {
        var expected = context(AppSessionSurface.APP, false, "PASSWORD", "+84:901234567");
        when(authService.recoverLogin(expected)).thenReturn(ApiResult.fail(409, "SESSION_REPLACED"));
        mockMvc.perform(sessionPost("/auth/users/login/attempts/" + ATTEMPT + "/recover", false)
                        .content("{\"installationId\":\"" + INSTALLATION + "\",\"executionSupported\":false,"
                                + "\"recoveryProof\":\"" + RECOVERY + "\",\"authMethod\":\"PASSWORD\","
                                + "\"authReference\":\"+84:901234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("SESSION_REPLACED"));
        verify(authService).recoverLogin(expected);
        verify(authService, org.mockito.Mockito.never()).login(any(), any(), any(AppLoginContext.class));
    }

    @Test
    void refreshResponseKeepsTheStoredGroupAndVersionDespiteNewContextHeaders() throws Exception {
        var stored = new ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity();
        stored.setSessionGroup("H5"); stored.setInstallationId("original-browser");
        stored.setAppSessionVersion(0L); stored.setSessionChainId("original-chain");
        stored.setExecutionSupported(false);
        var credential = new ffdd.opsconsole.auth.dto.UserRefreshRequest("refresh");
        when(refreshCookieService.resolve(any(), any())).thenReturn(credential);
        when(authService.refresh(eq(credential), eq(null), eq(false))).thenReturn(ApiResult.ok(
                new UserLoginResponse("access", "Bearer", new UserLoginResponse.UserSession(42L, "+84", "901234567", "synthetic"))
                        .withContext(stored, "SUCCEEDED")));
        mockMvc.perform(sessionPost("/auth/users/refresh", true)
                        .header("X-NexGrid-App-Session-Version", "999").content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionGroup").value("H5"))
                .andExpect(jsonPath("$.data.appSessionVersion").value(0))
                .andExpect(jsonPath("$.data.installationId").value("original-browser"))
                .andExpect(jsonPath("$.data.executionSupported").value(false));
        verify(authService).refresh(credential, null, false);
    }

    private static AppLoginContext context(AppSessionSurface group, boolean execution, String method, String reference) {
        return new AppLoginContext(group, INSTALLATION, execution, ATTEMPT, RECOVERY, method, reference);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sessionPost(String path, boolean execution) {
        return post(path).header(AppSessionSurface.APP_HEADER, "APP")
                .header("X-NexGrid-Installation-Id", INSTALLATION)
                .header("X-NexGrid-Execution-Supported", String.valueOf(execution))
                .header("X-NexGrid-Login-Attempt-Id", ATTEMPT)
                .header("X-NexGrid-Login-Recovery-Key", RECOVERY)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
