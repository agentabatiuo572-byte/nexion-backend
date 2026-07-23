package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.AppGenesisService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppGenesisController {
    private final AppGenesisService service;

    @GetMapping("/api/genesis/state")
    public ApiResult<Map<String, Object>> state() {
        return service.state();
    }

    @GetMapping("/api/genesis/account")
    public ApiResult<Map<String, Object>> account(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.account(userId);
    }

    @PostMapping("/api/genesis/purchase")
    public ApiResult<Map<String, Object>> purchase(
            @RequestBody AppGenesisService.PurchaseRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.purchase(userId, idempotencyKey, request);
    }

    @PostMapping("/api/genesis/holdings/{holdingNo}/listing")
    public ApiResult<Map<String, Object>> list(
            @PathVariable String holdingNo,
            @RequestBody AppGenesisService.ListingRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.list(userId, holdingNo, idempotencyKey, request);
    }

    @DeleteMapping("/api/genesis/holdings/{holdingNo}/listing")
    public ApiResult<Map<String, Object>> cancel(
            @PathVariable String holdingNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.cancel(userId, holdingNo, idempotencyKey);
    }

    @PostMapping("/api/genesis/listings/{holdingNo}/buy")
    public ApiResult<Map<String, Object>> buy(
            @PathVariable String holdingNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.buyListing(userId, holdingNo, idempotencyKey);
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
}
