package ffdd.opsconsole.onboarding.web;

import ffdd.opsconsole.onboarding.application.PhoneNativeSessionService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/onboarding/phone-installation")
@RequiredArgsConstructor
public class PhoneNativeSessionController {
    private final PhoneNativeSessionService service;
    private final ffdd.opsconsole.onboarding.application.OnboardingCalibrationService calibration;
    public record Challenge(String deviceId) { }
    @PostMapping("/login")
    public ApiResult<Map<String,Object>> login(@RequestBody Challenge request, Authentication authentication) {
        String deviceId = request == null ? null : request.deviceId();
        service.require(authentication,deviceId);
        return ApiResult.ok(calibration.phoneLogin(Long.valueOf(String.valueOf(authentication.getPrincipal())),deviceId));
    }
    @PostMapping("/challenge")
    public ApiResult<Map<String,Object>> challenge(@RequestBody Challenge request, Authentication authentication) {
        return ApiResult.ok(service.challenge(authentication,request == null ? null : request.deviceId()));
    }
    @PostMapping("/verify")
    public ApiResult<Map<String,Object>> verify(@RequestBody PhoneNativeSessionService.Proof request, Authentication authentication) {
        return ApiResult.ok(service.verify(authentication,request));
    }
}
