package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.FundsSandboxService;
import ffdd.opsconsole.finance.application.FundsSandboxService.OrderView;
import ffdd.opsconsole.finance.application.FundsSandboxService.Overview;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test")
@RequestMapping("/api/app/wallet/sandbox")
@RequiredArgsConstructor
public class FundsSandboxController {
    private final FundsSandboxService service;

    @GetMapping
    public ApiResult<Overview> overview(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : ApiResult.ok(service.overview(userId));
    }

    @PostMapping("/topups")
    public ApiResult<OrderView> topup(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TopupRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        return ApiResult.ok(service.createTopup(userId, request == null ? null : request.channel(),
                request == null ? null : request.amount(), idempotencyKey));
    }

    @PostMapping("/withdrawals")
    public ApiResult<OrderView> withdrawal(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) WithdrawalRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        return ApiResult.ok(service.createWithdrawal(userId, request == null ? null : request.channel(),
                request == null ? null : request.amount(), request == null ? null : request.targetAddress(),
                idempotencyKey));
    }

    @PostMapping("/orders/{orderNo}/callbacks")
    public ApiResult<OrderView> callback(
            @PathVariable String orderNo,
            @RequestBody(required = false) CallbackRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        return ApiResult.ok(service.applyCallback(userId, orderNo,
                request == null ? null : request.eventId(), request == null ? null : request.status(),
                request == null ? null : request.expectedVersion()));
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public record TopupRequest(String channel, BigDecimal amount) { }
    public record WithdrawalRequest(String channel, BigDecimal amount, String targetAddress) { }
    public record CallbackRequest(String eventId, String status, Long expectedVersion) { }
}
