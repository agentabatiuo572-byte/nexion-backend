package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppUserAuthService;
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
import ffdd.opsconsole.auth.dto.UserPasswordResetCompleteRequest;
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

    @PostMapping("/register")
    public ApiResult<UserLoginResponse> register(
            @RequestBody(required = false) UserRegistrationRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(
                registrationService.register(request, servletRequest.getRemoteAddr()),
                servletRequest, servletResponse);
    }

    @PostMapping("/login")
    public ApiResult<UserLoginResponse> login(
            @RequestBody(required = false) UserLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(authService.login(request, servletRequest.getRemoteAddr()),
                servletRequest, servletResponse);
    }

    @PostMapping("/oauth/exchange")
    public ApiResult<UserOAuthExchangeResponse> exchangeOAuth(
            @RequestBody(required = false) UserOAuthExchangeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issueOAuth(
                oauthService.exchange(request, servletRequest.getRemoteAddr(),
                        servletRequest.getHeader("Origin")),
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
                authService.completeOtpLogin(request, servletRequest.getRemoteAddr()),
                servletRequest, servletResponse);
    }

    @PostMapping("/password-reset/complete")
    public ApiResult<UserLoginResponse> completePasswordReset(
            @RequestBody(required = false) UserPasswordResetCompleteRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(
                authService.completePasswordReset(request, servletRequest.getRemoteAddr()),
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

    @PostMapping("/login/2fa")
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(
            @RequestBody(required = false) UserTwoFactorLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        return refreshCookieService.issue(
                authService.completeTwoFactorLogin(request, servletRequest.getRemoteAddr()),
                servletRequest, servletResponse);
    }

    @PostMapping("/refresh")
    public ApiResult<UserLoginResponse> refresh(
            @RequestBody(required = false) UserRefreshRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            ApiResult<UserLoginResponse> result = authService.refresh(
                    refreshCookieService.resolve(request, servletRequest));
            if (result.getCode() != 0 && refreshCookieService.cookieMode(servletRequest)) {
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
}
