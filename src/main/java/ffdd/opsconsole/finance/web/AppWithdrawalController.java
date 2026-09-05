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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/withdrawals")
@RequiredArgsConstructor
public class AppWithdrawalController {
    private final AppWithdrawalService service;

    @GetMapping
    public ApiResult<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? unauthorized() : service.list(userId, pageNum, pageSize);
    }

    @GetMapping("/policy")
    public ApiResult<Map<String, Object>> policy(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? unauthorized() : service.policy(userId);
    }

    @PostMapping("/eligibility")
    public ApiResult<Map<String, Object>> eligibility(
            @RequestBody EligibilityRequest request, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? unauthorized()
                : service.eligibility(userId, request.amount(), request.chain(), request.address(), request.policyVersion());
    }

    @GetMapping("/{withdrawalNo}")
    public ApiResult<Map<String, Object>> get(
            @PathVariable String withdrawalNo, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? unauthorized() : service.get(userId, withdrawalNo);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> submit(
            @RequestBody WithdrawalRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? unauthorized()
                : service.submit(userId, request.amount(), request.chain(), request.address(), request.policyVersion(),
                        Boolean.TRUE.equals(request.useNexFeeOffset()), idempotencyKey);
    }

    @PostMapping("/attempts/{idempotencyKey}/abandon")
    public ApiResult<Map<String, Object>> abandonAttempt(
            @PathVariable String idempotencyKey,
            @RequestBody WithdrawalRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? unauthorized()
                : service.abandonAttempt(userId, idempotencyKey, request.amount(), request.chain(), request.address(),
                        request.policyVersion(), Boolean.TRUE.equals(request.useNexFeeOffset()));
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

    private ApiResult<Map<String, Object>> unauthorized() {
        return ApiResult.fail(401, "USER_AUTH_REQUIRED");
    }

    public record WithdrawalRequest(
            BigDecimal amount,
            String chain,
            String address,
            String policyVersion,
            Boolean useNexFeeOffset) { }

    public record EligibilityRequest(BigDecimal amount, String chain, String address, String policyVersion) { }
}
