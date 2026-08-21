package ffdd.opsconsole.commerce.web;

import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppOrderCommandController {
    private final AppOrderCommandService service;

    @PostMapping({"/api/orders/{orderNo}/cancel", "/api/app/orders/{orderNo}/cancel"})
    public ApiResult<Map<String, Object>> cancel(@PathVariable String orderNo,
                                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                  Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.cancel(userId, orderNo, key);
    }

    @PostMapping({"/api/orders/{orderNo}/pay", "/api/app/orders/{orderNo}/pay"})
    public ApiResult<Map<String, Object>> pay(@PathVariable String orderNo,
                                               @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                               Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.pay(userId, orderNo, key);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long id = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
