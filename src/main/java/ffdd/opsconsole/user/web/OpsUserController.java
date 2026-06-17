package ffdd.opsconsole.user.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentRequest;
import ffdd.opsconsole.user.dto.UserImpersonationRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeRequest;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/users")
public class OpsUserController {
    private final OpsUserService userService;

    public OpsUserController(OpsUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return userService.overview();
    }

    @GetMapping("/profiles")
    public ApiResult<List<UserAccountView>> profiles(@ModelAttribute UserQueryRequest request) {
        return userService.profiles(request);
    }

    @GetMapping("/profiles/{userId}")
    public ApiResult<UserAccountView> profile(@PathVariable Long userId) {
        return userService.profile(userId);
    }

    @PatchMapping("/profiles/{userId}/status")
    public ApiResult<UserAccountView> updateStatus(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserStatusUpdateRequest request) {
        return userService.updateStatus(userId, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/impersonations")
    public ApiResult<Map<String, Object>> startImpersonation(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserImpersonationRequest request) {
        return userService.startImpersonation(userId, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/asset-adjustments")
    public ApiResult<Map<String, Object>> createAssetAdjustment(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserAssetAdjustmentRequest request) {
        return userService.createAssetAdjustment(userId, idempotencyKey, request);
    }

    @GetMapping("/sessions")
    public ApiResult<List<UserSessionView>> sessions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer limit) {
        return userService.sessions(userId, limit);
    }

    @PostMapping("/sessions/{refreshTokenId}/revoke")
    public ApiResult<UserSessionView> revokeSession(
            @PathVariable String refreshTokenId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserSessionRevokeRequest request) {
        return userService.revokeSession(refreshTokenId, idempotencyKey, request);
    }
}
