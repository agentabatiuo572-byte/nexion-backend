package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppUserAuthService;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserPasswordResetCompleteRequest;
import ffdd.opsconsole.auth.dto.UserTwoFactorLoginRequest;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
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

    @PostMapping("/login")
    public ApiResult<UserLoginResponse> login(@RequestBody(required = false) UserLoginRequest request,
                                               HttpServletRequest servletRequest) {
        return authService.login(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/password-reset/complete")
    public ApiResult<UserLoginResponse> completePasswordReset(
            @RequestBody(required = false) UserPasswordResetCompleteRequest request,
            HttpServletRequest servletRequest) {
        return authService.completePasswordReset(request, servletRequest.getRemoteAddr());
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
