package ffdd.opsconsole.onboarding.web;

import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding/calibrate")
@RequiredArgsConstructor
public class OnboardingCalibrationController {
    private final OnboardingCalibrationService service;

    @PostMapping
    public ApiResult<Map<String, Object>> calibrate(
            @RequestBody(required = false) OnboardingCalibrationService.Request request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (request != null && (request.idempotencyKey() == null || request.idempotencyKey().isBlank())
                && idempotencyKey != null) {
            request = new OnboardingCalibrationService.Request(request.deviceId(), request.expectedRevision(),
                    idempotencyKey, request.signals());
        }
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.calibrate(userId, request);
    }

    @GetMapping("/result")
    public ApiResult<Map<String, Object>> result(@RequestParam String deviceId, Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.result(userId, deviceId);
    }

    @PostMapping("/activate")
    public ApiResult<Map<String, Object>> activate(
            @RequestBody(required = false) OnboardingCalibrationService.ActionRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED")
                : service.activate(userId, action(request, idempotencyKey));
    }

    @PostMapping("/defer")
    public ApiResult<Map<String, Object>> defer(
            @RequestBody(required = false) OnboardingCalibrationService.ActionRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED")
                : service.defer(userId, action(request, idempotencyKey));
    }

    private OnboardingCalibrationService.ActionRequest action(
            OnboardingCalibrationService.ActionRequest request, String idempotencyKey) {
        if (request == null || request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) return request;
        return new OnboardingCalibrationService.ActionRequest(
                request.deviceId(), request.expectedRevision(), idempotencyKey);
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
