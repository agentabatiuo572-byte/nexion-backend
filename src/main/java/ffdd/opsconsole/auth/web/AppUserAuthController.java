package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppUserAuthService;
import ffdd.opsconsole.auth.application.AppSessionSurface;
import ffdd.opsconsole.auth.application.AppLoginContext;
import ffdd.opsconsole.shared.exception.BizException;
import org.springframework.web.bind.annotation.PathVariable;
import ffdd.opsconsole.auth.application.AppUserRegistrationService;
import ffdd.opsconsole.auth.application.AppUserRefreshCookieService;
import ffdd.opsconsole.auth.application.AppUserPasswordResetService;
import ffdd.opsconsole.auth.application.AppUserOAuthService;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeRequest;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeResponse;
import ffdd.opsconsole.auth.dto.UserOtpLoginChallengeResponse;
import ffdd.opsconsole.auth.dto.UserOtpLoginRequest;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.auth.dto.UserPasswordResetOtpCompleteRequest;
import ffdd.opsconsole.auth.dto.UserTwoFactorLoginRequest;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.auth.dto.UserRegistrationOtpRequest;
import ffdd.opsconsole.auth.dto.UserRegistrationOtpResponse;
import ffdd.opsconsole.auth.dto.UserRegistrationRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/auth/users")
@RequiredArgsConstructor
public class AppUserAuthController {
    private final AppUserAuthService authService;
    private final AppUserRegistrationService registrationService;
    private final AppUserPasswordResetService passwordResetService;
    private final AppUserOAuthService oauthService;
    private final AppUserRefreshCookieService refreshCookieService;

    @PostMapping("/register/otp/send")
    public ApiResult<UserRegistrationOtpResponse> sendRegistrationOtp(
            @RequestBody(required = false) UserRegistrationOtpRequest request,
            HttpServletRequest servletRequest) {
        return registrationService.sendOtp(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/register/otp/verify")
    public ApiResult<Map<String, Object>> verifyRegistrationOtp(
            @RequestBody(required = false) UserOtpLoginVerifyRequest request) {
        return registrationService.verifyOtp(request);
    }

    @PostMapping("/register")
    public ApiResult<UserLoginResponse> register(
            @RequestBody(required = false) UserRegistrationRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(
                registrationService.register(request, servletRequest.getRemoteAddr(), context(servletRequest, "REGISTRATION", request == null ? null : request.countryCode() + ":" + request.phone())),
                servletRequest, servletResponse);
    }

    @PostMapping("/login")
    public ApiResult<UserLoginResponse> login(
            @RequestBody(required = false) UserLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(authService.login(request, servletRequest.getRemoteAddr(), context(servletRequest, "PASSWORD", request == null ? null : request.countryCode() + ":" + request.phone())),
                servletRequest, servletResponse);
    }

    @PostMapping("/oauth/exchange")
    public ApiResult<UserOAuthExchangeResponse> exchangeOAuth(
            @RequestBody(required = false) UserOAuthExchangeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issueOAuth(
                oauthService.exchange(request, servletRequest.getRemoteAddr(),
                        servletRequest.getHeader("Origin"), context(servletRequest, "OAUTH", request == null ? null : request.provider() + ":" + request.challengeNo())),
                servletRequest, servletResponse);
    }

    @PostMapping("/login/otp/send")
    public ApiResult<UserOtpLoginChallengeResponse> beginOtpLogin(
            @RequestBody(required = false) UserOtpLoginRequest request,
            HttpServletRequest servletRequest) {
        return authService.beginOtpLogin(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/login/otp/verify")
    public ApiResult<UserLoginResponse> completeOtpLogin(
            @RequestBody(required = false) UserOtpLoginVerifyRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(
                authService.completeOtpLogin(request, servletRequest.getRemoteAddr(), context(servletRequest, "OTP", request == null ? null : request.challengeNo())),
                servletRequest, servletResponse);
    }

    @PostMapping("/password-reset/otp/send")
    public ApiResult<UserOtpLoginChallengeResponse> sendPasswordResetOtp(
            @RequestBody(required = false) UserOtpLoginRequest request,
            HttpServletRequest servletRequest) {
        return passwordResetService.send(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/password-reset/otp/complete")
    public ApiResult<Map<String, Object>> completePasswordResetOtp(
            @RequestBody(required = false) UserPasswordResetOtpCompleteRequest request,
            HttpServletRequest servletRequest) {
        return passwordResetService.complete(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/password-reset/otp/verify")
    public ApiResult<Map<String, Object>> verifyPasswordResetOtp(
            @RequestBody(required = false) UserOtpLoginVerifyRequest request) {
        return passwordResetService.verify(request);
    }

    @PostMapping("/login/2fa")
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(
            @RequestBody(required = false) UserTwoFactorLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(
                authService.completeTwoFactorLogin(request, servletRequest.getRemoteAddr(), context(servletRequest, "TWO_FACTOR", request == null ? null : request.challengeNo())),
                servletRequest, servletResponse);
    }

    @PostMapping("/refresh")
    public ApiResult<UserLoginResponse> refresh(
            @RequestBody(required = false) UserRefreshRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            ApiResult<UserLoginResponse> result = authService.refresh(
                    refreshCookieService.resolve(request, servletRequest),
                    servletRequest.getHeader(AppUserRefreshCookieService.ROTATION_KEY_HEADER),
                    refreshCookieService.cookieMode(servletRequest));
            if (result.getCode() != 0 && refreshCookieService.cookieMode(servletRequest)) {
                if ("USER_REFRESH_ROTATION_SUPERSEDED".equals(result.getMessage())) {
                    return result;
                }
                refreshCookieService.clear(servletResponse);
                return result;
            }
            return refreshCookieService.issue(result, servletRequest, servletResponse);
        } catch (RuntimeException failure) {
            if (refreshCookieService.cookieMode(servletRequest)) {
                refreshCookieService.clear(servletResponse);
            }
            throw failure;
        }
    }

    @PostMapping("/logout")
    public ApiResult<Map<String, Object>> logout(
            @RequestBody(required = false) UserRefreshRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            return authService.logout(refreshCookieService.resolve(request, servletRequest));
        } finally {
            refreshCookieService.clear(servletResponse);
        }
    }

    /** Discard the captured Bearer's session; a newer browser refresh cookie is unrelated. */
    @PostMapping("/session/discard")
    public ApiResult<Map<String, Object>> discardSession(Authentication authentication,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank())
            throw new BizException(401, "USER_AUTH_REQUIRED");
        return authService.discardSession(authentication);
    }

    public record LoginRecoveryRequest(String installationId, Boolean executionSupported, String recoveryProof,
            String authMethod, String authReference) { }

    @PostMapping("/login/attempts/{loginAttemptId}/recover")
    public ApiResult<UserLoginResponse> recover(@PathVariable String loginAttemptId,
            @RequestBody LoginRecoveryRequest request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        AppSessionSurface group = surface(servletRequest);
        if (request.executionSupported() == null) throw new BizException(422, "LOGIN_EXECUTION_CONTEXT_INVALID");
        return refreshCookieService.issue(authService.recoverLogin(new AppLoginContext(group, request.installationId(),
                request.executionSupported(), loginAttemptId, request.recoveryProof(), request.authMethod(),
                request.authReference())), servletRequest, servletResponse);
    }

    private AppLoginContext context(HttpServletRequest request, String method, String reference) {
        AppSessionSurface group = surface(request);
        String capability = request.getHeader("X-NexGrid-Execution-Supported");
        if (!"true".equals(capability) && !"false".equals(capability))
            throw new BizException(422, "LOGIN_EXECUTION_CONTEXT_INVALID");
        AppLoginContext context = new AppLoginContext(group, request.getHeader("X-NexGrid-Installation-Id"),
                Boolean.parseBoolean(capability), request.getHeader("X-NexGrid-Login-Attempt-Id"),
                request.getHeader("X-NexGrid-Login-Recovery-Key"), method, reference);
        context.validate();
        return context;
    }

    private AppSessionSurface surface(HttpServletRequest request) {
        return AppSessionSurface.from(refreshCookieService.cookieMode(request),
                request.getHeader(AppSessionSurface.APP_HEADER));
    }
}
