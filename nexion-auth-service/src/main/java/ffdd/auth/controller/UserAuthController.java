package ffdd.auth.controller;

import ffdd.auth.dto.RegisterSmsCodeRequest;
import ffdd.auth.dto.RegisterSmsCodeResponse;
import ffdd.auth.dto.ReferralSponsorResponse;
import ffdd.auth.dto.UserLoginRequest;
import ffdd.auth.dto.UserLoginResponse;
import ffdd.auth.dto.UserPreferenceResponse;
import ffdd.auth.dto.UserPreferenceUpdateRequest;
import ffdd.auth.dto.UserRegisterRequest;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.service.UserProfileService;
import ffdd.auth.service.UserAuthService;
import ffdd.common.api.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/users")
@RequiredArgsConstructor
public class UserAuthController {
    private final UserAuthService userAuthService;
    private final UserProfileService userProfileService;

    @PostMapping("/register/sms-code")
    public ApiResult<RegisterSmsCodeResponse> sendRegisterSmsCode(@Valid @RequestBody RegisterSmsCodeRequest request) {
        return ApiResult.ok(userAuthService.sendRegisterSmsCode(request));
    }

    @PostMapping("/register")
    public ApiResult<UserLoginResponse> register(@Valid @RequestBody UserRegisterRequest request) {
        return ApiResult.ok(userAuthService.register(request));
    }

    @PostMapping("/login")
    public ApiResult<UserLoginResponse> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResult.ok(userAuthService.login(request));
    }

    @GetMapping("/referrals/{code}")
    public ApiResult<ReferralSponsorResponse> referralSponsor(@PathVariable String code) {
        return ApiResult.ok(userAuthService.referralSponsor(code));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<UserResponse> current() {
        return ApiResult.ok(userProfileService.current());
    }

    @PatchMapping("/me")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<UserResponse> updateCurrent(@Valid @RequestBody UserUpdateRequest request) {
        return ApiResult.ok(userProfileService.update(request));
    }

    @GetMapping("/me/preferences")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<UserPreferenceResponse> currentPreferences() {
        return ApiResult.ok(userProfileService.currentPreferences());
    }

    @PatchMapping("/me/preferences")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<UserPreferenceResponse> updateCurrentPreferences(@RequestBody UserPreferenceUpdateRequest request) {
        return ApiResult.ok(userProfileService.updatePreferences(request));
    }
}
