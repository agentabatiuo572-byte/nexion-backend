package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.AppGenesisService;
import ffdd.opsconsole.market.application.AppGenesisSandboxFixtureService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppGenesisController {
    private final AppGenesisService service;
    private final AppGenesisSandboxFixtureService sandboxFixtures;

    @GetMapping("/api/genesis/state")
    public ApiResult<Map<String, Object>> state() {
        return service.state();
    }

    @GetMapping("/api/genesis/account")
    public ApiResult<Map<String, Object>> account(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.account(userId);
    }

    @GetMapping("/api/genesis/eligibility")
    public ApiResult<Map<String, Object>> eligibility(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.eligibility(userId);
    }

    /** Explicit local acceptance fixture; production fails closed in the service. */
    @PostMapping("/api/genesis/sandbox-fixture")
    public ApiResult<Map<String, Object>> sandboxFixture(Authentication authentication,
                                                         @RequestBody SandboxFixtureRequest request) {
        Long userId = userId(authentication);
        if (userId == null) return forbidden();
        String runId = request == null ? null : request.runId();
        sandboxFixtures.replace(runId, userId, request == null ? null : request.holders());
        return ApiResult.ok(Map.of("serverCanonical", true, "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", runId, "fixture", "GENESIS_HOLDER"));
    }

    @DeleteMapping("/api/genesis/sandbox-fixture")
    public ApiResult<Map<String, Object>> clearSandboxFixture(Authentication authentication,
                                                               @RequestParam String runId) {
        Long userId = userId(authentication);
        if (userId == null) return forbidden();
        sandboxFixtures.clear(runId, userId);
        return ApiResult.ok(Map.of("serverCanonical", true, "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", runId, "cleared", true));
    }

    @PostMapping("/api/genesis/purchase")
    public ApiResult<Map<String, Object>> purchase(
            @RequestBody AppGenesisService.PurchaseRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.purchase(userId, idempotencyKey, request);
    }

    @PostMapping("/api/genesis/invite/redeem")
    public ApiResult<Map<String,Object>> redeemInvite(@RequestBody InviteRedeemRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication){
        Long userId=userId(authentication);return userId==null?forbidden():service.redeemInvite(userId,idempotencyKey,request==null?null:request.code());
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

    public record InviteRedeemRequest(String code) { }
    public record SandboxFixtureRequest(String runId, java.util.List<AppGenesisSandboxFixtureService.HolderSpec> holders) { }
}
