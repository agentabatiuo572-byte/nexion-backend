package ffdd.opsconsole.user.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.user.application.OpsUser360Service;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountActionOverview;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentDetail;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserCredentialParamView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserKycLedgerRow;
import ffdd.opsconsole.user.domain.UserKycOverview;
import ffdd.opsconsole.user.domain.UserProfileExportFile;
import ffdd.opsconsole.user.domain.UserRegistrationRiskOverview;
import ffdd.opsconsole.user.domain.UserRegistrationRiskParamView;
import ffdd.opsconsole.user.domain.UserSecurityOverview;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.dto.UserAccountListRemoveRequest;
import ffdd.opsconsole.user.dto.UserAccountListUpsertRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentReviewRequest;
import ffdd.opsconsole.user.dto.UserCredentialParamUpdateRequest;
import ffdd.opsconsole.user.dto.UserImpersonationRequest;
import ffdd.opsconsole.user.dto.UserImpersonationTerminateRequest;
import ffdd.opsconsole.user.dto.UserKycExportRequest;
import ffdd.opsconsole.user.dto.UserKycNetworkUpdateRequest;
import ffdd.opsconsole.user.dto.UserKycStatusUpdateRequest;
import ffdd.opsconsole.user.dto.UserProfileExportRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.dto.UserRegistrationRiskParamUpdateRequest;
import ffdd.opsconsole.user.dto.UserSecurityActionRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeAllRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeRequest;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequiredArgsConstructor
public class OpsUserController {
    private final OpsUserService userService;
    private final OpsUser360Service user360Service;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('user_c1_read')")
    public ApiResult<Map<String, Object>> overview() {
        return userService.overview();
    }

    @GetMapping("/profiles")
    @PreAuthorize("hasAuthority('user_c1_read')")
    public ApiResult<PageResult<UserAccountView>> profiles(@ModelAttribute UserQueryRequest request) {
        return userService.profilePage(request);
    }

    @GetMapping("/kyc/overview")
    @PreAuthorize("hasAuthority('user_c4_read')")
    public ApiResult<UserKycOverview> kycOverview(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer limit) {
        return userService.kycOverview(status, pageNum, pageSize, limit);
    }

    @PatchMapping("/kyc/users/{userId}/status")
    @PreAuthorize("hasAuthority('user_c4_kyc_revoke')")
    public ApiResult<UserKycLedgerRow> updateKycStatus(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserKycStatusUpdateRequest request) {
        return userService.updateKycStatus(userId, idempotencyKey, request);
    }

    @PatchMapping("/kyc/network-whitelist")
    @PreAuthorize("hasAuthority('user_c4_write')")
    public ApiResult<Map<String, Object>> updateKycNetworkWhitelist(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserKycNetworkUpdateRequest request) {
        return userService.updateKycNetworkWhitelist(idempotencyKey, request);
    }

    @PostMapping("/kyc/exports")
    @PreAuthorize("hasAuthority('user_c4_write')")
    public ApiResult<Map<String, Object>> createKycExport(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserKycExportRequest request) {
        return userService.createKycExport(idempotencyKey, request);
    }

    @GetMapping("/profiles/{userId}")
    @PreAuthorize("hasAuthority('user_c1hub_read')")
    public ApiResult<UserAccountView> profile(@PathVariable Long userId) {
        return userService.profile(userId);
    }

    @PostMapping("/profiles/export")
    @PreAuthorize("hasAuthority('user_c1_write')")
    public ResponseEntity<?> exportProfiles(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) UserProfileExportRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return jsonFailure(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            return jsonFailure(OpsErrorCode.REASON_REQUIRED);
        }
        try {
            UserProfileExportFile file = userService.exportProfileExcel(idempotencyKey, request);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.ms-excel;charset=UTF-8"))
                    .contentLength(file.body().length)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                    .body(file.body());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(OpsErrorCode.VALIDATION_FAILED.httpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage()));
        }
    }

    @GetMapping("/account-actions/overview")
    @PreAuthorize("hasAuthority('user_c2_read')")
    public ApiResult<UserAccountActionOverview> accountActionOverview() {
        return userService.accountActionOverview();
    }

    @GetMapping("/account-actions/accounts/{userKey}")
    @PreAuthorize("hasAuthority('user_c2_read')")
    public ApiResult<UserAccountView> accountActionAccount(@PathVariable String userKey) {
        return userService.accountActionAccount(userKey);
    }

    @PostMapping("/account-lists")
    @PreAuthorize("hasAuthority('user_c2_write')")
    public ApiResult<UserAccountListEntryView> upsertAccountList(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserAccountListUpsertRequest request) {
        return userService.upsertAccountList(idempotencyKey, request);
    }

    @PostMapping("/account-lists/{userId}/remove")
    @PreAuthorize("hasAuthority('user_c2_write')")
    public ApiResult<UserAccountListEntryView> removeAccountList(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserAccountListRemoveRequest request) {
        return userService.removeAccountList(userId, idempotencyKey, request);
    }

    @GetMapping("/profiles/{userKey}/360")
    @PreAuthorize("hasAuthority('user_c1hub_read')")
    public ApiResult<Map<String, Object>> profile360(@PathVariable String userKey) {
        return user360Service.detail(userKey);
    }

    @GetMapping("/profiles/{userId}/security")
    @PreAuthorize("hasAuthority('user_c1hub_read')")
    public ApiResult<UserSecurityStatusView> securityStatus(@PathVariable Long userId) {
        return userService.securityStatus(userId);
    }

    @GetMapping("/security/overview")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<UserSecurityOverview> securityOverview(
            @RequestParam(required = false) String userKey,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer limit) {
        return userService.securityOverview(userKey, userId, pageNum, pageSize, limit);
    }

    @PatchMapping("/profiles/{userId}/status")
    @PreAuthorize("hasAnyAuthority('user_c1hub_account_freeze','user_c1hub_account_unfreeze')")
    public ApiResult<UserAccountView> updateStatus(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserStatusUpdateRequest request) {
        return userService.updateStatus(userId, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/impersonations")
    @PreAuthorize("hasAuthority('user_c2_impersonate_start')")
    public ApiResult<Map<String, Object>> startImpersonation(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserImpersonationRequest request) {
        return userService.startImpersonation(userId, idempotencyKey, request);
    }

    @PostMapping("/impersonations/{sessionNo}/terminate")
    @PreAuthorize("hasAuthority('user_c2_impersonate_terminate')")
    public ApiResult<UserImpersonationSessionView> terminateImpersonation(
            @PathVariable String sessionNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserImpersonationTerminateRequest request) {
        return userService.terminateImpersonation(sessionNo, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/sessions/revoke-all")
    @PreAuthorize("hasAuthority('user_c1hub_session_revoke_all')")
    public ApiResult<Map<String, Object>> revokeUserSessions(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserSessionRevokeAllRequest request) {
        return userService.revokeUserSessions(userId, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/asset-adjustments")
    @PreAuthorize("hasAuthority('user_c3_adjust_create')")
    public ApiResult<Map<String, Object>> createAssetAdjustment(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserAssetAdjustmentRequest request) {
        return userService.createAssetAdjustment(userId, idempotencyKey, request);
    }

    @GetMapping("/asset-adjustments/overview")
    @PreAuthorize("hasAuthority('user_c3_read')")
    public ApiResult<Map<String, Object>> assetAdjustmentOverview() {
        return userService.assetAdjustmentOverview();
    }

    @GetMapping("/assets/overview")
    @PreAuthorize("hasAuthority('user_c3_read')")
    public ApiResult<Map<String, Object>> assetsOverview() {
        return userService.assetAdjustmentOverview();
    }

    @GetMapping("/asset-adjustments")
    @PreAuthorize("hasAuthority('user_c3_read')")
    public ApiResult<PageResult<UserAssetAdjustmentView>> assetAdjustments(@ModelAttribute UserAssetAdjustmentQueryRequest request) {
        return userService.assetAdjustments(request);
    }

    @GetMapping("/asset-adjustments/{adjustmentNo}")
    @PreAuthorize("hasAuthority('user_c3_read')")
    public ApiResult<UserAssetAdjustmentDetail> assetAdjustmentDetail(@PathVariable String adjustmentNo) {
        return userService.assetAdjustmentDetail(adjustmentNo);
    }

    @PostMapping("/asset-adjustments/{adjustmentNo}/approve")
    @PreAuthorize("hasAuthority('user_c3_adjust_approve')")
    public ApiResult<UserAssetAdjustmentDetail> approveAssetAdjustment(
            @PathVariable String adjustmentNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserAssetAdjustmentReviewRequest request) {
        return userService.approveAssetAdjustment(adjustmentNo, idempotencyKey, request);
    }

    @PostMapping("/asset-adjustments/{adjustmentNo}/reject")
    @PreAuthorize("hasAuthority('user_c3_adjust_approve')")
    public ApiResult<UserAssetAdjustmentDetail> rejectAssetAdjustment(
            @PathVariable String adjustmentNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserAssetAdjustmentReviewRequest request) {
        return userService.rejectAssetAdjustment(adjustmentNo, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/security/disable-2fa")
    @PreAuthorize("hasAuthority('user_c1hub_2fa_reset')")
    public ApiResult<UserSecurityStatusView> disableTwoFactor(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserSecurityActionRequest request) {
        return userService.disableTwoFactor(userId, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/security/password-reset")
    @PreAuthorize("hasAuthority('user_c1hub_password_reset')")
    public ApiResult<UserSecurityStatusView> requestPasswordReset(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserSecurityActionRequest request) {
        return userService.requestPasswordReset(userId, idempotencyKey, request);
    }

    @PostMapping("/profiles/{userId}/security/unlock")
    @PreAuthorize("hasAuthority('user_c1hub_account_unfreeze')")
    public ApiResult<UserSecurityStatusView> unlockSecurity(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserSecurityActionRequest request) {
        return userService.unlockSecurity(userId, idempotencyKey, request);
    }

    @GetMapping("/security/credential-params")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<List<UserCredentialParamView>> credentialParams() {
        return userService.credentialParams();
    }

    @PatchMapping("/security/credential-params/{paramKey}")
    @PreAuthorize("hasAuthority('user_c5_write')")
    public ApiResult<UserCredentialParamView> updateCredentialParam(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserCredentialParamUpdateRequest request) {
        return userService.updateCredentialParam(paramKey, idempotencyKey, request);
    }

    @GetMapping("/registration-risk/overview")
    @PreAuthorize("hasAuthority('user_c6_read')")
    public ApiResult<UserRegistrationRiskOverview> registrationRiskOverview() {
        return userService.registrationRiskOverview();
    }

    @PatchMapping("/registration-risk/params/{paramKey}")
    @PreAuthorize("hasAuthority('user_c6_write')")
    public ApiResult<UserRegistrationRiskParamView> updateRegistrationRiskParam(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserRegistrationRiskParamUpdateRequest request) {
        return userService.updateRegistrationRiskParam(paramKey, idempotencyKey, request);
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<PageResult<UserSessionView>> sessions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer limit) {
        return userService.sessionPage(userId, pageNum, pageSize, limit);
    }

    @PostMapping("/sessions/{refreshTokenId}/revoke")
    @PreAuthorize("hasAuthority('user_c5_session_revoke_one')")
    public ApiResult<UserSessionView> revokeSession(
            @PathVariable String refreshTokenId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody UserSessionRevokeRequest request) {
        return userService.revokeSession(refreshTokenId, idempotencyKey, request);
    }

    private ResponseEntity<ApiResult<Map<String, Object>>> jsonFailure(OpsErrorCode errorCode) {
        return ResponseEntity.status(errorCode.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResult.fail(errorCode.httpStatus(), errorCode.name()));
    }
}
