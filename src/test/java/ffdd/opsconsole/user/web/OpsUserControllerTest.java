package ffdd.opsconsole.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.user.application.OpsUser360Service;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserProfileExportFile;
import ffdd.opsconsole.user.domain.UserProfileListView;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.dto.UserAccountListRemoveRequest;
import ffdd.opsconsole.user.dto.UserAccountListUpsertRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentReviewRequest;
import ffdd.opsconsole.user.dto.UserImpersonationTerminateRequest;
import ffdd.opsconsole.user.dto.UserKycExportRequest;
import ffdd.opsconsole.user.dto.UserKycNetworkUpdateRequest;
import ffdd.opsconsole.user.dto.UserKycStatusUpdateRequest;
import ffdd.opsconsole.user.dto.UserProfileExportRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.dto.UserRegistrationRiskParamUpdateRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeAllRequest;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class OpsUserControllerTest {
    private final OpsUserService userService = mock(OpsUserService.class);
    private final OpsUser360Service user360Service = mock(OpsUser360Service.class);
    private final AdminOperatorRoleResolver roleResolver = mock(AdminOperatorRoleResolver.class);
    private final OpsUserController controller = new OpsUserController(userService, user360Service, roleResolver);

    @Test
    void impersonationStartUsesItsDedicatedHighRiskPermission() {
        var method = java.util.Arrays.stream(OpsUserController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("startImpersonation"))
                .findFirst()
                .orElseThrow();
        String expression = method.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class).value();

        assertThat(expression).isEqualTo("hasAuthority('user_c2_impersonate_start')");
    }

    @Test
    void accountListMutationsUseTheSameDedicatedPermissionAsC2Buttons() {
        assertThat(preAuthorize("upsertAccountList"))
                .isEqualTo("hasAuthority('user_c2_blocklist_add')");
        assertThat(preAuthorize("removeAccountList"))
                .isEqualTo("hasAuthority('user_c2_blocklist_add')");
    }

    @Test
    void securityMutationsUseExistingC5PermissionsInsteadOfLegacyC1HubPermissions() {
        assertThat(preAuthorize("disableTwoFactor"))
                .isEqualTo("hasAuthority('user_c5_2fa_disable')");
        assertThat(preAuthorize("requestPasswordReset"))
                .isEqualTo("hasAuthority('user_c5_password_reset')");
        assertThat(preAuthorize("unlockSecurity"))
                .isEqualTo("hasAnyAuthority('user_c5_unlock_short','user_c5_unlock_long')");
    }

    private String preAuthorize(String methodName) {
        return java.util.Arrays.stream(OpsUserController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow()
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)
                .value();
    }

    @Test
    void overviewDelegatesToService() {
        when(userService.overview()).thenReturn(ApiResult.ok(Map.of("domain", "C")));

        ApiResult<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "C");
    }

    @Test
    void legacyAssetsOverviewDelegatesToAssetAdjustmentOverview() {
        when(userService.assetAdjustmentOverview()).thenReturn(ApiResult.ok(Map.of("domain", "C3")));

        ApiResult<Map<String, Object>> result = controller.assetsOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "C3");
        verify(userService).assetAdjustmentOverview();
    }

    @Test
    void profilesReturnPageResultAndDelegateQuery() {
        when(roleResolver.resolveCode()).thenReturn("SUPER_ADMIN");
        UserQueryRequest request = UserQueryRequest.basic("Alice", "ACTIVE", "PENDING", null, 1, 20, null);
        when(userService.profilePage(request)).thenReturn(ApiResult.ok(new PageResult<UserAccountView>(0, 1, 20, List.of())));

        ApiResult<PageResult<UserProfileListView>> result = controller.profiles(request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageSize()).isEqualTo(20);
        verify(userService).profilePage(eq(request));
    }

    @Test
    void supportProfileListHidesExactSecurityAndRiskFields() {
        when(roleResolver.resolveCode()).thenReturn("SUPPORT");
        UserQueryRequest request = UserQueryRequest.basic(null, null, null, null, 1, 50, null);
        UserAccountView account = new UserAccountView(
                52L, "U00000052", "Test User", "155****9999", "86", "ACTIVE", "APPROVED",
                "L1", "V0", true, new BigDecimal("1000"), new BigDecimal("500"), 13, "低风险",
                2L, 1L, LocalDateTime.of(2026, 7, 3, 15, 34), null);
        when(userService.profilePage(request)).thenReturn(ApiResult.ok(new PageResult<>(1, 1, 50, List.of(account))));

        UserProfileListView projected = controller.profiles(request).getData().getRecords().get(0);

        assertThat(projected.twoFactorEnabled()).isNull();
        assertThat(projected.riskScore()).isNull();
        assertThat(projected.riskBand()).isEqualTo("低风险");
        assertThat(projected.walletUsdt()).isEqualByComparingTo("1000");
        assertThat(projected.deviceCount()).isEqualTo(2L);
    }

    @Test
    void legacyUntrimmedProfileReadsAreNotExposedAsControllerRoutes() {
        assertThat(java.util.Arrays.stream(OpsUserController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .toList())
                .doesNotContain("/profiles/{userId}", "/profiles/{userId}/security");
    }

    @Test
    void profileExportRequiresOnlyIdempotencyKey() {
        UserProfileExportRequest request = UserProfileExportRequest.basic(
                null,
                null,
                null,
                null,
                "C1 masked user export",
                "superadmin");

        ResponseEntity<?> missingKey = controller.exportProfiles(" ", request);
        assertThat(missingKey.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void profileExportReturnsCsvDownload() {
        UserProfileExportRequest request = UserProfileExportRequest.basic(
                "Alice",
                "ACTIVE",
                null,
                null,
                "C1 masked user export",
                "superadmin");
        when(userService.exportProfileExcel("idem-c1-export", request))
                .thenReturn(new UserProfileExportFile("C1-USER-EXP-test.csv", "csv".getBytes(), 1));

        ResponseEntity<?> result = controller.exportProfiles("idem-c1-export", request);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("C1-USER-EXP-test.csv");
        assertThat(result.getHeaders().getContentType().toString()).startsWith("text/csv");
        verify(userService).exportProfileExcel(eq("idem-c1-export"), eq(request));
    }

    @Test
    void statusEndpointPassesIdempotencyKey() {
        UserStatusUpdateRequest request = new UserStatusUpdateRequest("FROZEN", "risk hold", "superadmin");
        controller.updateStatus(1L, "idem-c1", request);

        verify(userService).updateStatus(eq(1L), eq("idem-c1"), any(UserStatusUpdateRequest.class));
        assertThat(OpsAdminApi.ADMIN_PREFIX + "/users/profiles/{userId}/status")
                .startsWith("/api/admin/users");
    }

    @Test
    void kycStatusEndpointPassesIdempotencyKey() {
        UserKycStatusUpdateRequest request = new UserKycStatusUpdateRequest(
                "APPROVED",
                "offline verification passed",
                "superadmin");

        controller.updateKycStatus(1L, "idem-c4-status", request);

        verify(userService).updateKycStatus(eq(1L), eq("idem-c4-status"), any(UserKycStatusUpdateRequest.class));
    }

    @Test
    void kycOverviewEndpointDelegatesPageQuery() {
        controller.kycOverview("PENDING", 2, 10, null);

        verify(userService).kycOverview(eq("PENDING"), eq(2), eq(10), eq(null));
    }

    @Test
    void kycNetworkEndpointPassesIdempotencyKey() {
        UserKycNetworkUpdateRequest request = new UserKycNetworkUpdateRequest(
                "TRC20 / ERC20",
                "network policy cleanup",
                "superadmin");

        controller.updateKycNetworkWhitelist("idem-c4-network", request);

        verify(userService).updateKycNetworkWhitelist(eq("idem-c4-network"), any(UserKycNetworkUpdateRequest.class));
    }

    @Test
    void kycExportEndpointPassesIdempotencyKey() {
        UserKycExportRequest request = new UserKycExportRequest(
                "MASKED_LEDGER",
                "quarterly regulatory package",
                "superadmin");

        controller.createKycExport("idem-c4-export", request);

        verify(userService).createKycExport(eq("idem-c4-export"), any(UserKycExportRequest.class));
    }

    @Test
    void profile360DelegatesToAggregator() {
        when(user360Service.detail("usr_84F2")).thenReturn(ApiResult.ok(Map.of("summary", Map.of("userNo", "U00088421"))));

        ApiResult<Map<String, Object>> result = controller.profile360("usr_84F2");

        assertThat(result.getCode()).isZero();
        verify(user360Service).detail("usr_84F2");
    }

    @Test
    void accountActionOverviewDelegatesToService() {
        controller.accountActionOverview();

        verify(userService).accountActionOverview();
    }

    @Test
    void accountActionAccountDelegatesExactLookupToService() {
        controller.accountActionAccount("U00000051");

        verify(userService).accountActionAccount("U00000051");
    }

    @Test
    void accountListUpsertPassesIdempotencyKey() {
        UserAccountListUpsertRequest request = new UserAccountListUpsertRequest(
                1L,
                "BLOCK",
                "judicial request BR-0512",
                "superadmin",
                null);

        controller.upsertAccountList("idem-c2-list", request);

        verify(userService).upsertAccountList(eq("idem-c2-list"), any(UserAccountListUpsertRequest.class));
    }

    @Test
    void accountListRemovePassesIdempotencyKey() {
        UserAccountListRemoveRequest request = new UserAccountListRemoveRequest(
                "case cleared by compliance",
                "superadmin");

        controller.removeAccountList(1L, "idem-c2-list-remove", request);

        verify(userService).removeAccountList(eq(1L), eq("idem-c2-list-remove"), any(UserAccountListRemoveRequest.class));
    }

    @Test
    void impersonationTerminatePassesIdempotencyKey() {
        UserImpersonationTerminateRequest request = new UserImpersonationTerminateRequest(
                "support issue resolved",
                "superadmin");

        controller.terminateImpersonation("IMP-001", "idem-c2-imp-end", request);

        verify(userService).terminateImpersonation(eq("IMP-001"), eq("idem-c2-imp-end"), any(UserImpersonationTerminateRequest.class));
    }

    @Test
    void revokeAllSessionsPassesIdempotencyKey() {
        UserSessionRevokeAllRequest request = new UserSessionRevokeAllRequest(
                "risk containment",
                "superadmin");

        controller.revokeUserSessions(1L, "idem-c2-revoke-all", request);

        verify(userService).revokeUserSessions(eq(1L), eq("idem-c2-revoke-all"), any(UserSessionRevokeAllRequest.class));
    }

    @Test
    void sessionsEndpointReturnsPageResultAndDelegatesQuery() {
        when(userService.sessionPage(1L, 2, 25, null))
                .thenReturn(ApiResult.ok(new PageResult<UserSessionView>(0, 2, 25, List.of())));

        ApiResult<PageResult<UserSessionView>> result = controller.sessions(1L, 2, 25, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        verify(userService).sessionPage(eq(1L), eq(2), eq(25), eq(null));
    }

    @Test
    void assetAdjustmentListDelegatesQuery() {
        UserAssetAdjustmentQueryRequest request = new UserAssetAdjustmentQueryRequest("PENDING_REVIEW", "NEX", 1L, null, 1, 20, null);

        controller.assetAdjustments(request);

        verify(userService).assetAdjustments(eq(request));
    }

    @Test
    void assetAdjustmentDetailDelegates() {
        controller.assetAdjustmentDetail("ADJ-001");

        verify(userService).assetAdjustmentDetail("ADJ-001");
    }

    @Test
    void assetAdjustmentApprovePassesIdempotencyKey() {
        UserAssetAdjustmentReviewRequest request = new UserAssetAdjustmentReviewRequest(
                "reviewed ticket and ledger evidence",
                "superadmin");

        controller.approveAssetAdjustment("ADJ-001", "idem-c3-approve", request);

        verify(userService).approveAssetAdjustment(eq("ADJ-001"), eq("idem-c3-approve"), any(UserAssetAdjustmentReviewRequest.class));
    }

    @Test
    void assetAdjustmentRejectPassesIdempotencyKey() {
        UserAssetAdjustmentReviewRequest request = new UserAssetAdjustmentReviewRequest(
                "evidence does not match wallet ledger",
                "superadmin");

        controller.rejectAssetAdjustment("ADJ-001", "idem-c3-reject", request);

        verify(userService).rejectAssetAdjustment(eq("ADJ-001"), eq("idem-c3-reject"), any(UserAssetAdjustmentReviewRequest.class));
    }

    @Test
    void registrationRiskOverviewDelegatesToService() {
        controller.registrationRiskOverview();

        verify(userService).registrationRiskOverview();
    }

    @Test
    void registrationRiskParamEndpointPassesIdempotencyKey() {
        UserRegistrationRiskParamUpdateRequest request = new UserRegistrationRiskParamUpdateRequest(
                "6 次 / 20 分钟",
                "tighten login brute force guard",
                "superadmin",
                0L);

        controller.updateRegistrationRiskParam("lockShort", "idem-c6-lock", request);

        verify(userService).updateRegistrationRiskParam(
                eq("lockShort"),
                eq("idem-c6-lock"),
                any(UserRegistrationRiskParamUpdateRequest.class));
    }
}
