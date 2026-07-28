package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import ffdd.opsconsole.finance.dto.AppVietQrIntentCancelRequest;
import ffdd.opsconsole.finance.dto.AppVietQrIntentCreateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppVietQrIntentController {
    private final AppVietQrIntentService service;

    @GetMapping("/payments/config")
    public ApiResult<Map<String, Object>> paymentConfig(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.paymentConfig();
    }

    @GetMapping("/payments/fx-quote")
    public ApiResult<Map<String, Object>> fxQuote(
            @RequestParam String fiat,
            @RequestParam String asset,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.fxQuote(fiat, asset);
    }

    @PostMapping("/deposits/vietqr/intents")
    public ApiResult<Map<String, Object>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) AppVietQrIntentCreateRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.create(userId, idempotencyKey, request == null ? null : request.usdtAmount());
    }

    @GetMapping("/deposits/vietqr/intents")
    public ApiResult<Map<String, Object>> list(
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.list(userId, limit);
    }

    @GetMapping("/deposits/vietqr/intents/{intentNo}")
    public ApiResult<Map<String, Object>> get(
            @PathVariable String intentNo,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.get(userId, intentNo);
    }

    @PostMapping("/deposits/vietqr/intents/{intentNo}/cancel")
    public ApiResult<Map<String, Object>> cancel(
            @PathVariable String intentNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) AppVietQrIntentCancelRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.cancel(
                        userId, intentNo, idempotencyKey,
                        request == null ? null : request.expectedVersion());
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
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
