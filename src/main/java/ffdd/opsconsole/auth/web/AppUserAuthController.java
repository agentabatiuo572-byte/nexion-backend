package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppUserAuthService;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.shared.api.ApiResult;
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
}
