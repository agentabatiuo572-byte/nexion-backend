package ffdd.opsconsole.onboarding.web;

import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.onboarding.application.PhoneInstallationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/onboarding/phone-installation")
@RequiredArgsConstructor
public class PhoneInstallationController {
    private final PhoneInstallationService service;
    private final OnboardingCalibrationService calibration;
    public record InstallationRequest(String deviceId) { }

    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody(required = false) InstallationRequest request,
            Authentication authentication) {
        String deviceId = request == null ? null : request.deviceId();
        service.require(authentication, deviceId);
        return ApiResult.ok(calibration.phoneLogin(Long.valueOf(String.valueOf(authentication.getPrincipal())), deviceId));
    }

    @PostMapping("/challenge")
    @ResponseStatus(org.springframework.http.HttpStatus.GONE)
    public ApiResult<Map<String, Object>> challenge() {
        return ApiResult.fail(410, "PHONE_NATIVE_PROOF_RETIRED");
    }

    @PostMapping("/verify")
    @ResponseStatus(org.springframework.http.HttpStatus.GONE)
    public ApiResult<Map<String, Object>> verify() {
        return ApiResult.fail(410, "PHONE_NATIVE_PROOF_RETIRED");
    }
}
