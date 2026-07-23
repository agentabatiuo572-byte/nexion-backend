package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.AppTradeinService;
import ffdd.opsconsole.device.dto.AppTradeinConfigResponse;
import ffdd.opsconsole.device.dto.AppTradeinQuoteRequest;
import ffdd.opsconsole.device.dto.AppTradeinQuoteResponse;
import ffdd.opsconsole.device.dto.AppTradeinSubmitRequest;
import ffdd.opsconsole.device.dto.AppTradeinSubmitResponse;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppTradeinController {
    private final AppTradeinService tradeinService;

    @GetMapping("/api/app/trade-in/config")
    public ApiResult<AppTradeinConfigResponse> config(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : tradeinService.config(userId);
    }

    @PostMapping({"/api/app/trade-in/quote", "/api/devices/replace/quote"})
    public ApiResult<AppTradeinQuoteResponse> quote(
            @RequestBody(required = false) AppTradeinQuoteRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : tradeinService.quote(userId, request);
    }

    @PostMapping({"/api/app/trade-in/submit", "/api/devices/replace"})
    public ApiResult<AppTradeinSubmitResponse> submit(
            @RequestBody(required = false) AppTradeinSubmitRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : tradeinService.submit(userId, idempotencyKey, request);
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
}
