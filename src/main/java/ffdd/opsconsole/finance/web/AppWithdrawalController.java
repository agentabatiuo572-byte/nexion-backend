package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.AppWithdrawalService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
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
@RequestMapping("/api/withdrawals")
@RequiredArgsConstructor
public class AppWithdrawalController {
    private final AppWithdrawalService service;

    @GetMapping
    public ApiResult<Map<String, Object>> list(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.list(userId);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> submit(
            @RequestBody WithdrawalRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.submit(userId, request.amount(), request.chain(), request.address(), idempotencyKey);
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

    public record WithdrawalRequest(BigDecimal amount, String chain, String address) { }
}
