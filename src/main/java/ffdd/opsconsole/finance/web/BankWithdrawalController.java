package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.BankWithdrawalService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/withdrawals/bank")
public class BankWithdrawalController {
    private final BankWithdrawalService service;
    @GetMapping("/config") public ApiResult<Map<String, Object>> config(Authentication auth) { return service.config(user(auth)); }
    @PostMapping("/beneficiary/otp") public ApiResult<Map<String, Object>> otp(Authentication auth) { return service.sendOtp(user(auth)); }
    @PostMapping("/beneficiary") public ApiResult<Map<String, Object>> bind(Authentication auth,
            @RequestHeader("Idempotency-Key") String key, @RequestBody BankWithdrawalService.BindRequest request) {
        return service.bind(user(auth), request, key);
    }
    @PostMapping("/quotes") public ApiResult<Map<String, Object>> quote(Authentication auth, @RequestBody QuoteRequest request) {
        return service.quote(user(auth), request.amountUsdt());
    }
    @GetMapping("/quotes/{quoteNo}") public ApiResult<Map<String, Object>> recover(Authentication auth, @PathVariable String quoteNo) {
        return service.recoverQuote(user(auth), quoteNo);
    }
    @PostMapping("/quotes/{quoteNo}/abandon") public ApiResult<Map<String, Object>> abandon(Authentication auth, @PathVariable String quoteNo) {
        return service.abandonQuote(user(auth), quoteNo);
    }
    @PostMapping("/orders") public ApiResult<Map<String, Object>> submit(Authentication auth,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SubmitRequest request) {
        return service.submit(user(auth), request.quoteNo(), key);
    }
    @GetMapping("/orders/{orderNo}") public ApiResult<Map<String, Object>> get(Authentication auth, @PathVariable String orderNo) {
        return service.orderView(user(auth), orderNo);
    }
    private long user(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))) throw new BizException(401, "USER_AUTH_REQUIRED");
        try { long id = Long.parseLong(String.valueOf(auth.getPrincipal())); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new BizException(401, "USER_AUTH_REQUIRED");
    }
    public record QuoteRequest(BigDecimal amountUsdt) {}
    public record SubmitRequest(String quoteNo) {}
}
