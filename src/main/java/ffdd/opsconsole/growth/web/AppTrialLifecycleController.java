package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
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

    @GetMapping("/state")
    public ApiResult<Map<String, Object>> state(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.state(userId);
    }

    @PostMapping("/start")
    public ApiResult<Map<String, Object>> start(
            @RequestBody(required = false) StartRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        StartRequest body = request == null ? new StartRequest(null, null) : request;
        return userId == null ? forbidden()
                : service.start(userId, body.paymentMethodId(), body.deviceName(), idempotencyKey);
    }

    @PostMapping("/cancel")
    public ApiResult<Map<String, Object>> cancel(
            @RequestBody(required = false) CancelRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.cancel(userId, request == null ? null : request.reason(), idempotencyKey);
    }

    @PostMapping("/extension")
    public ApiResult<Map<String, Object>> extension(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.extend(userId, idempotencyKey);
    }

    @PostMapping("/redeem-early")
    public ApiResult<Map<String, Object>> redeemEarly(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.redeemEarly(userId, idempotencyKey);
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
}
