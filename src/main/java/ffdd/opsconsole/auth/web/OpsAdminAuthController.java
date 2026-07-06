package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.OpsAdminAuthService;
import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.dto.AdminPasswordChangeRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/auth")
@RequiredArgsConstructor
public class OpsAdminAuthController {
    private final OpsAdminAuthService authService;

    @PostMapping("/login")
    public ApiResult<AdminLoginResponse> login(@RequestBody(required = false) AdminLoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public ApiResult<AdminLoginResponse.AdminSession> me(Authentication authentication) {
        return authService.current(authentication);
    }

    @PostMapping("/password/change")
    public ApiResult<AdminLoginResponse> changePassword(
            Authentication authentication,
            @RequestBody(required = false) AdminPasswordChangeRequest request) {
        return authService.changePassword(authentication, request);
    }
}
