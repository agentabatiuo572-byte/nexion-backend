package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.AppPayoutAddressService;
import ffdd.opsconsole.finance.application.AppPayoutAddressService.SaveRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payout-addresses")
@RequiredArgsConstructor
public class AppPayoutAddressController {
    private final AppPayoutAddressService service;

    @GetMapping
    public ApiResult<Map<String, Object>> list(Authentication authentication) {
        return service.list(userId(authentication));
    }

    @PostMapping("/otp/send")
    public ApiResult<Map<String, Object>> sendOtp(Authentication authentication) {
        return service.sendOtp(userId(authentication));
    }

    @PutMapping
    public ApiResult<Map<String, Object>> save(
            Authentication authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) SaveRequest request) {
        return service.save(userId(authentication), request, idempotencyKey);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
        try {
            long value = Long.parseLong(authentication.getName());
            if (value <= 0) throw new NumberFormatException("non-positive");
            return value;
        } catch (RuntimeException ex) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
    }
}
