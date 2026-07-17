package ffdd.opsconsole.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.user.application.OpsUser360Service;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserProfileExportFile;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class OpsUserControllerTest {
    private final OpsUserService userService = mock(OpsUserService.class);
    private final OpsUser360Service user360Service = mock(OpsUser360Service.class);
    private final OpsUserController controller = new OpsUserController(userService, user360Service);

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
        UserQueryRequest request = new UserQueryRequest("Alice", "ACTIVE", "PENDING", null, 1, 20, null);
        when(userService.profilePage(request)).thenReturn(ApiResult.ok(new PageResult<UserAccountView>(0, 1, 20, List.of())));

        ApiResult<PageResult<UserAccountView>> result = controller.profiles(request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageSize()).isEqualTo(20);
        verify(userService).profilePage(eq(request));
    }

    @Test
    void profileExportRequiresIdempotencyKeyAndReason() {
        UserProfileExportRequest request = new UserProfileExportRequest(
                null,
                null,
                null,
                null,
                "C1 masked user export",
                "superadmin");

        ResponseEntity<?> missingKey = controller.exportProfiles(" ", request);
        ResponseEntity<?> missingReason = controller.exportProfiles(
                "idem-c1-export",
                new UserProfileExportRequest(null, null, null, null, " ", "superadmin"));

        assertThat(missingKey.getStatusCode().value()).isEqualTo(422);
        assertThat(missingReason.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void profileExportReturnsExcelDownload() {
        UserProfileExportRequest request = new UserProfileExportRequest(
                "Alice",
                "ACTIVE",
                null,
                null,
                "C1 masked user export",
                "superadmin");
        when(userService.exportProfileExcel("idem-c1-export", request))
                .thenReturn(new UserProfileExportFile("C1-USER-EXP-test.xls", "excel".getBytes(), 1));

        ResponseEntity<?> result = controller.exportProfiles("idem-c1-export", request);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("C1-USER-EXP-test.xls");
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
                "superadmin");

        controller.updateRegistrationRiskParam("lockShort", "idem-c6-lock", request);

        verify(userService).updateRegistrationRiskParam(
                eq("lockShort"),
                eq("idem-c6-lock"),
                any(UserRegistrationRiskParamUpdateRequest.class));
    }
}
