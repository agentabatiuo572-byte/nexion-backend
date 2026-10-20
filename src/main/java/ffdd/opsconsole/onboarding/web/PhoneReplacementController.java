package ffdd.opsconsole.onboarding.web;

import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.onboarding.application.AppPhoneReplacementService;
import ffdd.opsconsole.onboarding.application.AppPhoneReplacementService.RequestInput;
import ffdd.opsconsole.onboarding.application.AppPhoneReplacementService.SupportInput;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/onboarding/phone-installation")
@RequiredArgsConstructor
public class PhoneReplacementController {
    private final AppPhoneReplacementService service;

    @GetMapping("/replacement-eligibility")
    public ApiResult<Eligibility> eligibility(Authentication authentication) {
        return ApiResult.ok(service.eligibility(authentication));
    }

    @GetMapping("/replacement-requests/current")
    public ApiResult<CurrentRequest> currentRequest(Authentication authentication) {
        return ApiResult.ok(service.currentRequest(authentication));
    }

    @GetMapping("/replacement-operations/by-key")
    public ApiResult<Receipt> byKey(Authentication authentication, @RequestParam String commandType,
            @RequestParam String idempotencyKey) {
        return ApiResult.ok(service.operationByKey(authentication, commandType, idempotencyKey));
    }

    @GetMapping("/replacement-operations/{operationId}")
    public ApiResult<Receipt> operation(Authentication authentication, @PathVariable String operationId) {
        return ApiResult.ok(service.operation(authentication, operationId));
    }

    @PostMapping("/replacement-requests")
    public ApiResult<Receipt> create(Authentication authentication, @RequestBody RequestInput request,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResult.ok(service.createRequest(authentication, request, key));
    }

    @PostMapping("/replacement-requests/{requestId}/support-link")
    public ApiResult<Receipt> link(Authentication authentication, @PathVariable String requestId,
            @RequestBody SupportInput request, @RequestHeader("Idempotency-Key") String key) {
        return ApiResult.ok(service.linkSupport(authentication, requestId, request, key));
    }
}
