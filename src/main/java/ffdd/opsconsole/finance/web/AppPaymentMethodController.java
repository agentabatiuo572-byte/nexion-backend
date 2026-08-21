package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.AppPaymentMethodService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-methods")
@RequiredArgsConstructor
public class AppPaymentMethodController {
    private final AppPaymentMethodService service;
    @GetMapping public ApiResult<Map<String, Object>> list(Authentication authentication) { return service.list(userId(authentication)); }
    @PostMapping("/bind") public ApiResult<Map<String, Object>> bind(Authentication authentication, @RequestHeader(name="Idempotency-Key", required=false) String key, @RequestBody(required=false) AppPaymentMethodService.BindRequest request) { return service.bind(userId(authentication), request, key); }
    @PostMapping("/{methodId}/unbind") public ApiResult<Map<String, Object>> unbind(Authentication authentication, @PathVariable Long methodId, @RequestHeader(name="Idempotency-Key", required=false) String key, @RequestBody(required=false) PaymentMethodCommand request) { return service.unbind(userId(authentication), methodId, request == null ? null : request.expectedVersion(), key); }
    @PostMapping("/{methodId}/default") public ApiResult<Map<String, Object>> setDefault(Authentication authentication, @PathVariable Long methodId, @RequestHeader(name="Idempotency-Key", required=false) String key, @RequestBody(required=false) PaymentMethodCommand request) { return service.setDefault(userId(authentication), methodId, request == null ? null : request.expectedVersion(), key); }
    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getDetails() instanceof Map<?, ?> details) || !"USER".equals(String.valueOf(details.get("subjectType")))) throw new BizException(401, "USER_AUTH_REQUIRED");
        try { long id = Long.parseLong(authentication.getName()); if (id <= 0) throw new NumberFormatException(); return id; } catch (RuntimeException ex) { throw new BizException(401, "USER_AUTH_REQUIRED"); }
    }
    public record PaymentMethodCommand(Long expectedVersion) { }
}
