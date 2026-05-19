package ffdd.auth.controller;

import ffdd.auth.dto.UserLoginRequest;
import ffdd.auth.dto.UserLoginResponse;
import ffdd.auth.dto.UserRegisterRequest;
import ffdd.auth.service.UserAuthService;
import ffdd.common.api.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/users")
@RequiredArgsConstructor
public class UserAuthController {
    private final UserAuthService userAuthService;

    @PostMapping("/register")
    public ApiResult<UserLoginResponse> register(@Valid @RequestBody UserRegisterRequest request) {
        return ApiResult.ok(userAuthService.register(request));
    }

    @PostMapping("/login")
    public ApiResult<UserLoginResponse> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResult.ok(userAuthService.login(request));
    }
}

