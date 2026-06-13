package ffdd.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.dto.UserImpersonationEndRequest;
import ffdd.auth.dto.UserImpersonationSessionResponse;
import ffdd.auth.dto.UserImpersonationStartRequest;
import ffdd.auth.dto.UserQueryRequest;
import ffdd.auth.dto.UserPasswordResetLinkRequest;
import ffdd.auth.dto.UserPasswordResetLinkResponse;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserSearchResponse;
import ffdd.auth.dto.UserSessionResponse;
import ffdd.auth.dto.UserSessionRevokeRequest;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserTwoFactorAdminRequest;
import ffdd.auth.dto.UserTwoFactorAdminResponse;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.service.UserOpsService;
import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/users")
@RequiredArgsConstructor
public class UserOpsController {
    private final UserOpsService userOpsService;
    private final AuditLogService auditLogService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('PERM_USER_READ')")
    public ApiResult<Page<UserResponse>> page(@ModelAttribute UserQueryRequest query,
                                              @RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "20") long size) {
        return ApiResult.ok(userOpsService.page(current, size, query));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('PERM_USER_READ')")
    public ApiResult<List<UserSearchResponse>> search(@RequestParam String keyword,
                                                      @RequestParam(defaultValue = "10") int limit) {
        return ApiResult.ok(userOpsService.search(keyword, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_READ')")
    public ApiResult<UserResponse> detail(@PathVariable Long id) {
        return ApiResult.ok(userOpsService.detail(id));
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('PERM_USER_READ')")
    public ApiResult<List<UserSessionResponse>> listSessions(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(userOpsService.listSessions(userId, limit));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return ApiResult.ok(userOpsService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusUpdateRequest request) {
        return ApiResult.ok(userOpsService.updateStatus(id, request));
    }

    @PostMapping("/{id}/password-reset-link")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserPasswordResetLinkResponse> requestPasswordResetLink(
            @PathVariable Long id,
            @Valid @RequestBody UserPasswordResetLinkRequest request) {
        UserPasswordResetLinkResponse response = userOpsService.requestPasswordResetLink(id, request);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("USER_PASSWORD_RESET_LINK_REQUESTED")
                .resourceType("USER")
                .resourceId(String.valueOf(id))
                .bizNo(response.getResetRequestNo())
                .userId(id)
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "resetRequestNo", response.getResetRequestNo(),
                        "deliveryStatus", response.getDeliveryStatus(),
                        "recipientMasked", response.getRecipientMasked(),
                        "operator", response.getOperator()))
                .build());
        return ApiResult.ok(response);
    }

    @PostMapping("/{id}/impersonations")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserImpersonationSessionResponse> startImpersonation(
            @PathVariable Long id,
            @Valid @RequestBody UserImpersonationStartRequest request) {
        UserImpersonationSessionResponse response = userOpsService.startImpersonation(id, request);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("USER_IMPERSONATION_STARTED")
                .resourceType("USER_IMPERSONATION")
                .resourceId(String.valueOf(id))
                .bizNo(response.getSessionNo())
                .userId(id)
                .riskLevel("HIGH")
                .detail(Map.of(
                        "sessionNo", response.getSessionNo(),
                        "status", response.getStatus(),
                        "operator", response.getOperator(),
                        "ttlMinutes", response.getTtlMinutes()))
                .build());
        return ApiResult.ok(response);
    }

    @PostMapping("/impersonations/{sessionNo}/end")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserImpersonationSessionResponse> endImpersonation(
            @PathVariable String sessionNo,
            @Valid @RequestBody UserImpersonationEndRequest request) {
        UserImpersonationSessionResponse response = userOpsService.endImpersonation(sessionNo, request);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("USER_IMPERSONATION_ENDED")
                .resourceType("USER_IMPERSONATION")
                .resourceId(sessionNo)
                .bizNo(sessionNo)
                .userId(response.getUserId())
                .riskLevel("HIGH")
                .detail(Map.of(
                        "sessionNo", response.getSessionNo(),
                        "status", response.getStatus(),
                        "operator", response.getOperator()))
                .build());
        return ApiResult.ok(response);
    }

    @PostMapping("/sessions/{refreshTokenId}/revoke")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserSessionResponse> revokeSession(
            @PathVariable String refreshTokenId,
            @Valid @RequestBody UserSessionRevokeRequest request) {
        UserSessionResponse response = userOpsService.revokeSession(refreshTokenId, request);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("USER_SESSION_REVOKED")
                .resourceType("USER_SESSION")
                .resourceId(refreshTokenId)
                .bizNo(refreshTokenId)
                .userId(response.getUserId())
                .riskLevel("HIGH")
                .detail(Map.of(
                        "refreshTokenId", response.getRefreshTokenId(),
                        "status", response.getStatus(),
                        "operator", request.getOperator()))
                .build());
        return ApiResult.ok(response);
    }

    @PostMapping("/{id}/two-factor/disable")
    @PreAuthorize("hasAuthority('PERM_USER_WRITE')")
    public ApiResult<UserTwoFactorAdminResponse> disableTwoFactor(
            @PathVariable Long id,
            @Valid @RequestBody UserTwoFactorAdminRequest request) {
        UserTwoFactorAdminResponse response = userOpsService.disableTwoFactor(id, request);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("USER_TWO_FACTOR_DISABLED")
                .resourceType("USER_SECURITY")
                .resourceId(String.valueOf(id))
                .bizNo("2FA-" + id)
                .userId(id)
                .riskLevel("HIGH")
                .detail(Map.of(
                        "twoFactorEnabled", response.getTwoFactorEnabled(),
                        "operator", response.getOperator()))
                .build());
        return ApiResult.ok(response);
    }
}
