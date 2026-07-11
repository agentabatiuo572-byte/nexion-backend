package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.AdminAccountCreateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.platform.dto.AdminAccountPasswordResetResponse;
import ffdd.opsconsole.platform.dto.AdminAccountProfileUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountRoleUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountSecurityBaselineUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacActionCreateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacGrantUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform")
@PreAuthorize("hasAuthority('platform_a1_read')")
@RequiredArgsConstructor
public class OpsAdminAccountController {
    private final OpsAdminAccountService accountService;

    @GetMapping("/accounts/overview")
    public ApiResult<AdminAccountOverview> overview() {
        return accountService.overview();
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<AdminAccountOverview.OperatorRecord> createAccount(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AdminAccountCreateRequest request) {
        return accountService.createAccount(idempotencyKey, request);
    }

    @PatchMapping("/accounts/{accountId}/role")
    @PreAuthorize("hasAuthority('platform_a1_account_role_change')")
    public ApiResult<AdminAccountOverview.OperatorRecord> changeRole(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountRoleUpdateRequest request) {
        return accountService.changeRole(idempotencyKey, accountId, request);
    }

    @PatchMapping("/accounts/{accountId}/profile")
    @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<AdminAccountOverview.OperatorRecord> updateProfile(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountProfileUpdateRequest request) {
        return accountService.updateProfile(idempotencyKey, accountId, request);
    }

    @PatchMapping("/accounts/{accountId}/status")
    @PreAuthorize("hasAuthority('platform_a1_account_disable')")
    public ApiResult<AdminAccountOverview.OperatorRecord> updateStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountStatusUpdateRequest request) {
        return accountService.updateStatus(idempotencyKey, accountId, request);
    }

    @DeleteMapping("/accounts/{accountId}")
    @PreAuthorize("hasAuthority('platform_a1_account_delete')")
    public ApiResult<AdminAccountOverview.OperatorRecord> deleteAccount(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return accountService.deleteAccount(idempotencyKey, accountId, request);
    }

    @PostMapping("/accounts/{accountId}/reset-2fa")
    @PreAuthorize("hasAuthority('platform_a1_account_2fa_reset')")
    public ApiResult<AdminAccountOverview.OperatorRecord> reset2fa(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return accountService.reset2fa(idempotencyKey, accountId, request);
    }

    @PostMapping("/accounts/{accountId}/password/reset")
    @PreAuthorize("hasAuthority('platform_a1_account_password_reset')")
    public ApiResult<AdminAccountPasswordResetResponse> resetPassword(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return accountService.resetPassword(idempotencyKey, accountId, request);
    }

    @PostMapping("/accounts/{accountId}/sessions/revoke")
    @PreAuthorize("hasAuthority('platform_a1_account_sessions_revoke')")
    public ApiResult<AdminAccountOverview.OperatorRecord> revokeSessions(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return accountService.revokeSessions(idempotencyKey, accountId, request);
    }

    @PatchMapping("/accounts/security-baselines/{baselineKey}")
    @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<AdminAccountOverview.SecurityBaseline> updateSecurityBaseline(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String baselineKey,
            @RequestBody(required = false) AdminAccountSecurityBaselineUpdateRequest request) {
        return accountService.updateSecurityBaseline(idempotencyKey, baselineKey, request);
    }

    @PatchMapping("/rbac/actions/{actionId}/grants")
    @PreAuthorize("hasAuthority('platform_a1_rbac_grants_update')")
    public ApiResult<AdminAccountOverview.RbacAction> updateRbacGrants(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String actionId,
            @RequestBody(required = false) AdminRbacGrantUpdateRequest request) {
        return accountService.updateRbacGrants(idempotencyKey, actionId, request);
    }

    @PostMapping("/rbac/actions")
    @PreAuthorize("hasAuthority('platform_a1_rbac_grants_update')")
    public ApiResult<AdminAccountOverview.RbacAction> registerRbacAction(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AdminRbacActionCreateRequest request) {
        return accountService.registerRbacAction(idempotencyKey, request);
    }
}
