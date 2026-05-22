package ffdd.auth.controller;

import ffdd.auth.dto.AdminChangePasswordRequest;
import ffdd.auth.dto.AdminLoginRequest;
import ffdd.auth.dto.AdminLoginResponse;
import ffdd.auth.dto.AdminProfileResponse;
import ffdd.auth.dto.AdminProfileUpdateRequest;
import ffdd.auth.service.AdminAuthService;
import ffdd.common.api.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/admin")
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public ApiResult<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return ApiResult.ok(adminAuthService.login(request));
    }

    @GetMapping("/me")
    public ApiResult<AdminProfileResponse> current() {
        return ApiResult.ok(adminAuthService.current());
    }

    @PutMapping("/profile")
    public ApiResult<AdminProfileResponse> updateProfile(@RequestBody AdminProfileUpdateRequest request) {
        return ApiResult.ok(adminAuthService.updateProfile(request));
    }

    @PutMapping("/password")
    public ApiResult<Void> changePassword(@Valid @RequestBody AdminChangePasswordRequest request) {
        adminAuthService.changePassword(request);
        return ApiResult.ok();
    }
}
