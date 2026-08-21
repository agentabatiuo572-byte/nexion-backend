package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.commerce.application.CommerceSandboxTrialService;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trial")
@RequiredArgsConstructor
public class AppTrialLifecycleController {
    private final AppTrialLifecycleService service;
    private final CommerceSandboxTrialService sandboxService;
    private final Environment environment;

    @GetMapping("/state")
    public ApiResult<Map<String, Object>> state(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : sandbox() ? sandboxService.state(userId) : service.state(userId);
    }

    @PostMapping("/start")
    public ApiResult<Map<String, Object>> start(
            @RequestBody(required = false) StartRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        StartRequest body = request == null ? new StartRequest(null, null) : request;
        return userId == null ? forbidden()
                : sandbox() ? sandboxService.start(userId, idempotencyKey)
                : service.start(userId, body.paymentMethodId(), body.deviceName(), idempotencyKey);
    }

    @PostMapping("/cancel")
    public ApiResult<Map<String, Object>> cancel(
            @RequestBody(required = false) CancelRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : sandbox() ? sandboxService.cancel(userId)
                : service.cancel(userId, request == null ? null : request.reason(), idempotencyKey);
    }

    @PostMapping("/extension")
    public ApiResult<Map<String, Object>> extension(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : sandbox()
                ? ApiResult.fail(409, "TRIAL_SANDBOX_COMMAND_UNSUPPORTED") : service.extend(userId, idempotencyKey);
    }

    @PostMapping("/redeem-early")
    public ApiResult<Map<String, Object>> redeemEarly(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : sandbox()
                ? ApiResult.fail(409, "TRIAL_SANDBOX_COMMAND_UNSUPPORTED") : service.redeemEarly(userId, idempotencyKey);
    }

    @PostMapping("/convert")
    public ApiResult<Map<String, Object>> convert(
            @RequestBody(required = false) ConvertRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : sandbox() ? sandboxService.convert(userId, request == null ? null : request.productNo(),
                        request == null ? null : request.expectedAmountUsdt(), idempotencyKey)
                : service.convert(userId, request == null ? null : request.productNo(), idempotencyKey);
    }

    private boolean sandbox() {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(profiles)) {
            if (sandboxService == null || !sandboxService.enabled()) {
                throw new BizException(503, "TRIAL_SANDBOX_UNAVAILABLE");
            }
            return true;
        }
        if (profiles == null || profiles.length == 0
                || profiles.length == 1 && Set.of("prod").contains(profiles[0])) {
            return false;
        }
        throw new BizException(503, "TRIAL_RUNTIME_PROFILE_UNSUPPORTED");
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

    public record StartRequest(Long paymentMethodId, String deviceName) {
    }

    public record CancelRequest(String reason) {
    }

    public record ConvertRequest(String productNo, BigDecimal expectedAmountUsdt) {
    }
}
