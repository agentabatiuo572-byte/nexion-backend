package ffdd.opsconsole.user.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.application.OpsUserPaymentMethodService;
import ffdd.opsconsole.user.dto.UserPaymentMethodCommandRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/users")
@RequiredArgsConstructor
public class OpsUserPaymentMethodController {
    private final OpsUserPaymentMethodService service;

    @GetMapping("/profiles/{userId}/payment-methods")
    @PreAuthorize("hasAuthority('user_c1hub_read')")
    public ApiResult<Map<String, Object>> list(@PathVariable Long userId,
                                               @RequestParam(defaultValue = "false") boolean includeUnbound,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResult.ok(service.list(userId, includeUnbound, page, pageSize));
    }

    @PostMapping("/profiles/{userId}/payment-methods/{methodId}/unbind")
    @PreAuthorize("hasAuthority('user_c1hub_write')")
    public ApiResult<Map<String, Object>> unbind(@PathVariable Long userId, @PathVariable Long methodId,
                                                 @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
                                                 @RequestBody UserPaymentMethodCommandRequest request) {
        return ApiResult.ok(service.unbind(userId, methodId, key, request));
    }

    @PostMapping("/profiles/{userId}/payment-methods/{methodId}/rebind-notification")
    @PreAuthorize("hasAuthority('user_c1hub_write')")
    public ApiResult<Map<String, Object>> notifyRebind(@PathVariable Long userId, @PathVariable Long methodId,
                                                       @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
                                                       @RequestBody UserPaymentMethodCommandRequest request) {
        return ApiResult.ok(service.notifyRebind(userId, methodId, key, request));
    }

    @PostMapping("/profiles/{userId}/nickname/reset")
    @PreAuthorize("hasAuthority('user_c1hub_write')")
    public ApiResult<Map<String, Object>> resetNickname(@PathVariable Long userId,
                                                        @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
                                                        @RequestBody UserPaymentMethodCommandRequest request) {
        return ApiResult.ok(service.resetNickname(userId, key, request));
    }
}
