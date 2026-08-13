package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppUserAuthService;
import ffdd.opsconsole.auth.application.AppUserRegistrationService;
import ffdd.opsconsole.auth.application.AppUserPasswordResetService;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
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

    @PostMapping("/register/otp/send")
    public ApiResult<UserRegistrationOtpResponse> sendRegistrationOtp(
            @RequestBody(required = false) UserRegistrationOtpRequest request,
            HttpServletRequest servletRequest) {
        return registrationService.sendOtp(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/register")
    public ApiResult<UserLoginResponse> register(
            @RequestBody(required = false) UserRegistrationRequest request,
            HttpServletRequest servletRequest) {
        return registrationService.register(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/login")
    public ApiResult<UserLoginResponse> login(@RequestBody(required = false) UserLoginRequest request,
                                               HttpServletRequest servletRequest) {
        return authService.login(request, servletRequest.getRemoteAddr());
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
            HttpServletRequest servletRequest) {
        return authService.completeOtpLogin(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/password-reset/complete")
    public ApiResult<UserLoginResponse> completePasswordReset(
            @RequestBody(required = false) UserPasswordResetCompleteRequest request,
            HttpServletRequest servletRequest) {
        return authService.completePasswordReset(request, servletRequest.getRemoteAddr());
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
            HttpServletRequest servletRequest) {
        return authService.completeTwoFactorLogin(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/refresh")
    public ApiResult<UserLoginResponse> refresh(@RequestBody(required = false) UserRefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public ApiResult<Map<String, Object>> logout(@RequestBody(required = false) UserRefreshRequest request) {
        return authService.logout(request);
    }
}
