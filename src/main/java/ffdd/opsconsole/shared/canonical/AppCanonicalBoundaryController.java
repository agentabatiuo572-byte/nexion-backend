package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequiredArgsConstructor
public class AppCanonicalBoundaryController {
    private final AppCanonicalBoundaryService service;
    private final AppTrialLifecycleService trialLifecycleService;

    @GetMapping("/api/trial/eligibility")
    public ApiResult<Map<String, Object>> trialEligibility(
            @RequestParam(required = false) String clientStatus, Authentication authentication) {
        Long userId = userId(authentication);
        if (userId == null) return forbidden();
        ApiResult<Map<String, Object>> result = trialLifecycleService.state(userId);
        if (clientStatus != null && result.getData() != null) {
            String serverState = String.valueOf(result.getData().get("state"));
            if (!serverState.equalsIgnoreCase(clientStatus.trim())) {
                return ApiResult.fail(409, "TRIAL_STATE_CONFLICT");
            }
        }
        return result;
    }

    @GetMapping("/api/kyc/status")
    public ApiResult<Map<String, Object>> kycStatus(
            @RequestParam(required = false) Boolean clientWalletPaired, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.kycStatus(userId, clientWalletPaired);
    }

    @GetMapping("/api/kyc/status/{userId}")
    @PreAuthorize("hasAuthority('user_c4_read')")
    public ApiResult<Map<String, Object>> adminKycStatus(@PathVariable Long userId) {
        return service.kycStatus(userId, null);
    }

    @GetMapping("/api/security/state")
    public ApiResult<Map<String, Object>> securityState(
            @RequestParam(required = false) Boolean clientTwoFactorEnabled, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.securityState(userId, clientTwoFactorEnabled);
    }

    @GetMapping("/api/product/phase")
    public ApiResult<Map<String, Object>> productPhase(
            @RequestParam(required = false) String clientPinned,
            @RequestParam(name = "dev", defaultValue = "false") String dev,
            Authentication authentication) {
        Long userId = userId(authentication);
        boolean devMode = "1".equals(dev) || "true".equalsIgnoreCase(dev);
        return userId == null ? forbidden() : service.productPhase(userId, clientPinned, devMode);
    }

    @PostMapping("/api/devices/activate")
    public ApiResult<Map<String, Object>> activateDevice(
            @RequestBody DeviceActivateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.activateDevice(userId, request.deviceId(), request.clientMaxDevices(), idempotencyKey);
    }

    @GetMapping("/api/devices/earnings")
    public ApiResult<Map<String, Object>> deviceEarnings(
            @RequestParam(name = "_devSeedLegacyDevice", defaultValue = "false") boolean seedLegacyDevice,
            @RequestParam(name = "_devFastForwardAll", defaultValue = "false") boolean fastForwardAll,
            @RequestParam(name = "_devBumpEarningsTotal", required = false) BigDecimal bumpedEarningsTotal,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.deviceEarnings(userId, seedLegacyDevice, fastForwardAll, bumpedEarningsTotal);
    }

    @PostMapping("/api/auth/otp/verify")
    public ApiResult<Map<String, Object>> verifyOtp(
            @RequestBody OtpVerifyRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.verifyOtp(userId, request.challengeNo(), request.code(), request.clientRegexAccepted(), idempotencyKey);
    }

    @PostMapping("/api/wallet/bills")
    public ApiResult<Map<String, Object>> pushClientBill(
            @RequestBody Map<String, Object> request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.pushClientBill(userId, request, idempotencyKey);
    }

    @PostMapping("/api/orders")
    public ApiResult<Map<String, Object>> createOrder(
            @RequestBody OrderCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.createOrder(userId, request.orderNo(), request.productId(), request.productNo(),
                        request.quantity(), request.voucherId(), idempotencyKey);
    }

    @GetMapping("/api/orders")
    public ApiResult<Map<String, Object>> orders(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.orders(userId);
    }

    @PostMapping("/api/trial/charge")
    public ApiResult<Map<String, Object>> chargeTrial(
            @RequestBody(required = false) TrialChargeRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (userId == null) return forbidden();
        TrialChargeRequest body = request == null ? new TrialChargeRequest(null, null) : request;
        if (body.chargeSucceeded() != null || body.chargeFailRate() != null) {
            return ApiResult.fail(409, "CLIENT_CHARGE_OUTCOME_REJECTED");
        }
        return trialLifecycleService.charge(userId, idempotencyKey);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ApiResult<Map<String, Object>> forbidden() {
        return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
    }

    public record DeviceActivateRequest(Long deviceId, Integer clientMaxDevices) {
    }

    public record OtpVerifyRequest(String challengeNo, String code, Boolean clientRegexAccepted) {
    }

    public record OrderCreateRequest(
            String orderNo, Long productId, String productNo, Integer quantity, String voucherId) {
    }

    public record TrialChargeRequest(Boolean chargeSucceeded, BigDecimal chargeFailRate) {
    }
}
