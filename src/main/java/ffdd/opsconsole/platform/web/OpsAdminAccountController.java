package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.AdminAccountCreateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.platform.dto.AdminAccountRoleUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountSecurityBaselineUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacActionCreateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacGrantUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
@RequiredArgsConstructor
public class OpsAdminAccountController {
    private final OpsAdminAccountService accountService;

    @GetMapping("/accounts/overview")
    public ApiResult<AdminAccountOverview> overview() {
        return accountService.overview();
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<AdminAccountOverview.OperatorRecord> createAccount(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AdminAccountCreateRequest request) {
        return accountService.createAccount(idempotencyKey, request);
    }

    @PatchMapping("/accounts/{accountId}/role")
    @PreAuthorize("hasAnyAuthority('PERM_SYSTEM_WRITE','PERM_SUPPORT_SEAT_WRITE')")
    public ApiResult<AdminAccountOverview.OperatorRecord> changeRole(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountRoleUpdateRequest request) {
        return accountService.changeRole(idempotencyKey, accountId, request);
    }

    @PatchMapping("/accounts/{accountId}/status")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<AdminAccountOverview.OperatorRecord> updateStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountStatusUpdateRequest request) {
        return accountService.updateStatus(idempotencyKey, accountId, request);
    }

    @PostMapping("/accounts/{accountId}/reset-2fa")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<AdminAccountOverview.OperatorRecord> reset2fa(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return accountService.reset2fa(idempotencyKey, accountId, request);
    }

    @PostMapping("/accounts/{accountId}/sessions/revoke")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<AdminAccountOverview.OperatorRecord> revokeSessions(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String accountId,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return accountService.revokeSessions(idempotencyKey, accountId, request);
    }

    @PatchMapping("/accounts/security-baselines/{baselineKey}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<AdminAccountOverview.SecurityBaseline> updateSecurityBaseline(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String baselineKey,
            @RequestBody(required = false) AdminAccountSecurityBaselineUpdateRequest request) {
        return accountService.updateSecurityBaseline(idempotencyKey, baselineKey, request);
    }

    @PatchMapping("/rbac/actions/{actionId}/grants")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<AdminAccountOverview.RbacAction> updateRbacGrants(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String actionId,
            @RequestBody(required = false) AdminRbacGrantUpdateRequest request) {
        return accountService.updateRbacGrants(idempotencyKey, actionId, request);
    }

    @PostMapping("/rbac/actions")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<AdminAccountOverview.RbacAction> registerRbacAction(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AdminRbacActionCreateRequest request) {
        return accountService.registerRbacAction(idempotencyKey, request);
    }
}
