package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.AppExchangeService;
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
public class AppExchangeController {
    private final AppExchangeService service;

    @GetMapping("/api/config/exchange/caps")
    public ApiResult<Map<String,Object>> caps() { return service.caps(); }

    @GetMapping("/api/config/market/nex")
    public ApiResult<Map<String,Object>> market() { return service.market(); }

    @GetMapping("/api/market/nex")
    public ApiResult<Map<String,Object>> marketAlias() { return service.market(); }

    @GetMapping("/api/exchange")
    public ApiResult<Map<String,Object>> state(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.state(userId);
    }

    @PostMapping("/api/exchange")
    public ApiResult<Map<String,Object>> swap(@RequestBody AppExchangeService.SwapRequest request,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.swap(userId,idempotencyKey,request);
    }

    @PostMapping("/api/exchange/{exchangeNo}/cancel")
    public ApiResult<Map<String,Object>> cancel(@PathVariable String exchangeNo,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.cancel(userId,exchangeNo,idempotencyKey);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?,?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long id = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) { return null; }
    }
    private ApiResult<Map<String,Object>> forbidden() { return ApiResult.fail(403,"USER_SUBJECT_REQUIRED"); }
}
