package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.AppRepurchaseService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppRepurchaseController {
    private final AppRepurchaseService service;

    @GetMapping("/api/config/repurchase")
    public ApiResult<Map<String, Object>> config() {
        return service.config();
    }

    @GetMapping("/api/repurchase/orders")
    public ApiResult<Map<String, Object>> orders(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.orders(userId);
    }

    @PostMapping("/api/repurchase/orders")
    public ApiResult<Map<String, Object>> open(@RequestBody AppRepurchaseService.OpenRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.open(userId, idempotencyKey, request);
    }

    @PostMapping("/api/repurchase/orders/{orderNo}/claim")
    public ApiResult<Map<String, Object>> claim(@PathVariable String orderNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.claim(userId, orderNo, idempotencyKey);
    }

    @PostMapping("/api/repurchase/orders/{orderNo}/early-withdraw")
    public ApiResult<Map<String, Object>> earlyWithdraw(@PathVariable String orderNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.earlyWithdraw(userId, orderNo, idempotencyKey);
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
}
