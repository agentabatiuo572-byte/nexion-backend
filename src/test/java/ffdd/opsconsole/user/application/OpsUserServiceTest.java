package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAccountActionOverview;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentDetail;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserCredentialParamView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserKycLedgerRow;
import ffdd.opsconsole.user.domain.UserKycOverview;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserProfileExportFile;
import ffdd.opsconsole.user.domain.UserRegistrationRiskOverview;
import ffdd.opsconsole.user.domain.UserRegistrationRiskParamView;
import ffdd.opsconsole.user.domain.UserSecurityOverview;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSecurityUserRow;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import ffdd.opsconsole.user.domain.User360Seed;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsUserServiceTest {
    private final FakeUserOpsRepository userRepository = new FakeUserOpsRepository();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsUserService service = new OpsUserService(userRepository, coverageFacade, configFacade, auditLogService);

    @Test
    void overviewKeepsSunsetCapabilitiesHistoricalOnly() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("sunsetCompatibility").toString())
                .contains("Premium history")
                .contains("NEX v2 maturity")
                .contains("Points adjustments are rejected");
    }

    @Test
    void statusChangeRequiresIdempotencyKey() {
        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                null,
                new UserStatusUpdateRequest("FROZEN", "risk hold", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void statusChangeWritesAudit() {
        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                "idem-c2",
                new UserStatusUpdateRequest("FROZEN", "risk hold", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("FROZEN");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C2_USER_STATUS_CHANGED");
    }

    @Test
    void profilePageReturnsServerCanonicalPagination() {
        ApiResult<PageResult<UserAccountView>> result = service.profilePage(
                new UserQueryRequest("Alice", "FROZEN,BANNED,RESTRICTED", "PENDING", 70, 2, 10, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        assertThat(result.getData().getPageSize()).isEqualTo(10);
        assertThat(result.getData().getRecords()).containsExactly(userRepository.user);
        assertThat(userRepository.lastProfileRequest.status()).isEqualTo("FROZEN,BANNED,RESTRICTED");
        assertThat(userRepository.lastProfileRequest.riskMin()).isEqualTo(70);
    }

    @Test
    void profilePageSeedsC1AccountsWhenMissingThenQueriesAgain() {
        userRepository.c2SeedPresent = false;

        ApiResult<PageResult<UserAccountView>> result = service.profilePage(
                new UserQueryRequest("Marcus", null, null, null, 1, 10, null));

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.accountActionSeedWrites).isEqualTo(1);
        assertThat(result.getData().getRecords())
                .extracting(UserAccountView::userNo)
                .contains("U00008807");
        assertThat(userRepository.lastProfileRequest.keyword()).isEqualTo("Marcus");
    }

    @Test
    void profileExportUsesServerPaginationAndWritesAudit() {
        UserProfileExportFile file = service.exportProfileExcel(
                "idem-c1-export",
                new UserProfileExportRequest("Alice", "ACTIVE", null, 30, "C1 masked user export", "superadmin"));

        String workbook = new String(file.body(), StandardCharsets.UTF_8);
        assertThat(file.fileName()).startsWith("C1-USER-EXP-").endsWith(".xls");
        assertThat(workbook)
                .contains("用户编码")
                .contains("U00000001")
                .doesNotContain("userId")
                .doesNotContain("<th>ID</th>");
        assertThat(userRepository.lastProfileRequest.status()).isEqualTo("ACTIVE");
        assertThat(userRepository.lastProfileRequest.riskMin()).isEqualTo(30);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C1_USER_PROFILE_MASKED_EXPORTED");
    }

    @Test
    void accountActionOverviewReturnsServerCanonicalListsAndImpersonations() {
        userRepository.sessions.put("rt-existing", new UserSessionView(
                1L, "rt-existing", "web", "10.0.0.*", "ACTIVE",
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1), null));
        userRepository.accountLists.add(new UserAccountListEntryView(
                1L, "U00000001", "Alice", "ALLOW", "offline diligence passed", "ACTIVE",
                LocalDateTime.now().plusDays(30), "risklead_h", LocalDateTime.now().minusDays(1),
                null, null, null));
        userRepository.impersonations.add(new UserImpersonationSessionView(
                "IMP-001", 1L, "U00000001", "Alice", "ACTIVE", 30, "cs_amy",
                "support troubleshooting", LocalDateTime.now().plusMinutes(20), LocalDateTime.now().minusMinutes(10),
                null, null, null, 20L));

        ApiResult<UserAccountActionOverview> result = service.accountActionOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accountLists()).hasSize(1);
        assertThat(result.getData().impersonations()).hasSize(1);
        assertThat(result.getData().trustListCount()).isEqualTo(1);
        assertThat(result.getData().activeImpersonations()).isEqualTo(1);
        assertThat(result.getData().sources()).contains("nx_account_list", "nx_user_impersonation_session");
    }

    @Test
    void accountActionOverviewSeedsC2RowsWhenActionRowsAreMissing() {
        userRepository.c2SeedPresent = true;

        ApiResult<UserAccountActionOverview> result = service.accountActionOverview();

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.accountActionSeedWrites).isEqualTo(1);
        assertThat(result.getData().accounts()).extracting(UserAccountView::userNo).contains("U00008807");
        assertThat(result.getData().sessions()).extracting(UserSessionView::userId).contains(8807L);
        assertThat(result.getData().accountLists()).extracting(UserAccountListEntryView::kind).contains("ALLOW");
        assertThat(result.getData().impersonations()).extracting(UserImpersonationSessionView::sessionNo).contains("IMP-204");
    }

    @Test
    void accountActionOverviewSeedsC2RowsWhenSeedUsersAreMissing() {
        userRepository.c2SeedPresent = false;

        ApiResult<UserAccountActionOverview> result = service.accountActionOverview();

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.accountActionSeedWrites).isEqualTo(1);
        assertThat(result.getData().accounts()).extracting(UserAccountView::userNo).contains("U00008807");
        assertThat(result.getData().sessions()).extracting(UserSessionView::userId).contains(8807L);
        assertThat(result.getData().accountLists()).extracting(UserAccountListEntryView::kind).contains("ALLOW");
        assertThat(result.getData().impersonations()).extracting(UserImpersonationSessionView::sessionNo).contains("IMP-204");
    }

    @Test
    void accountListUpsertRequiresIdempotencyKey() {
        ApiResult<UserAccountListEntryView> result = service.upsertAccountList(
                null,
                new UserAccountListUpsertRequest(1L, "ALLOW", "offline diligence passed", "risklead_h", null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void accountListUpsertRejectsDuplicateActiveKindWith409() {
        userRepository.accountLists.add(new UserAccountListEntryView(
                1L, "U00000001", "Alice", "ALLOW", "offline diligence passed", "ACTIVE",
                null, "risklead_h", LocalDateTime.now().minusDays(1), null, null, null));

        ApiResult<UserAccountListEntryView> result = service.upsertAccountList(
                "idem-c2-list",
                new UserAccountListUpsertRequest(1L, "ALLOW", "duplicate allowlist", "risklead_h", null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void accountListUpsertAndRemoveWriteAudit() {
        ApiResult<UserAccountListEntryView> upserted = service.upsertAccountList(
                "idem-c2-list",
                new UserAccountListUpsertRequest(1L, "BLOCK", "judicial request BR-0512", "superadmin", null));
        ApiResult<UserAccountListEntryView> removed = service.removeAccountList(
                1L,
                "idem-c2-list-remove",
                new UserAccountListRemoveRequest("case cleared by compliance", "superadmin"));

        assertThat(upserted.getCode()).isZero();
        assertThat(upserted.getData().kind()).isEqualTo("BLOCK");
        assertThat(removed.getCode()).isZero();
        assertThat(removed.getData().status()).isEqualTo("REMOVED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, org.mockito.Mockito.atLeast(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLogWriteRequest::getAction)
                .contains("C2_ACCOUNT_LIST_UPSERTED", "C2_ACCOUNT_LIST_REMOVED");
    }

    @Test
    void terminatingImpersonationWritesAuditAndRejectsDuplicate() {
        userRepository.impersonations.add(new UserImpersonationSessionView(
                "IMP-001", 1L, "U00000001", "Alice", "ACTIVE", 30, "cs_amy",
                "support troubleshooting", LocalDateTime.now().plusMinutes(20), LocalDateTime.now().minusMinutes(10),
                null, null, null, 20L));

        ApiResult<UserImpersonationSessionView> first = service.terminateImpersonation(
                "IMP-001",
                "idem-c2-imp-end",
                new UserImpersonationTerminateRequest("support issue resolved", "superadmin"));
        ApiResult<UserImpersonationSessionView> second = service.terminateImpersonation(
                "IMP-001",
                "idem-c2-imp-end-2",
                new UserImpersonationTerminateRequest("second end", "superadmin"));

        assertThat(first.getCode()).isZero();
        assertThat(first.getData().status()).isEqualTo("TERMINATED");
        assertThat(second.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C2_USER_IMPERSONATION_TERMINATED");
    }

    @Test
    void startingImpersonationWritesC2Audit() {
        ApiResult<Map<String, Object>> result = service.startImpersonation(
                1L,
                "idem-c2-imp-start",
                new UserImpersonationRequest(30, "support troubleshooting", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "ACTIVE").containsEntry("ttlMinutes", 30);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C2_USER_IMPERSONATION_STARTED");
    }

    @Test
    void revokingAllUserSessionsUsesServerSessionsAndAudit() {
        userRepository.sessions.put("rt-active", new UserSessionView(
                1L, "rt-active", "web", "10.0.0.*", "ACTIVE", LocalDateTime.now(), LocalDateTime.now().plusDays(1), null));

        ApiResult<Map<String, Object>> result = service.revokeUserSessions(
                1L,
                "idem-c2-revoke-all",
                new UserSessionRevokeAllRequest("risk containment", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("revokedCount", 1);
        assertThat(userRepository.sessions.get("rt-active").status()).isEqualTo("REVOKED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C2_USER_SESSIONS_REVOKED");
    }

    @Test
    void sessionPageReturnsServerCanonicalPagination() {
        userRepository.sessions.put("rt-active", new UserSessionView(
                1L, "rt-active", "web", "10.0.0.*", "ACTIVE", LocalDateTime.now(), LocalDateTime.now().plusDays(1), null));

        ApiResult<PageResult<UserSessionView>> result = service.sessionPage(1L, 2, 25, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isEqualTo(1);
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        assertThat(result.getData().getPageSize()).isEqualTo(25);
        assertThat(userRepository.lastSessionPageUserId).isEqualTo(1L);
    }

    @Test
    void kycOverviewBuildsRowsFromUserDomainAndConfig() {
        configFacade.values.put("kyc.network_whitelist", "TRC20 / ERC20");

        ApiResult<UserKycOverview> result = service.kycOverview(null, 2, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().networkWhitelist()).isEqualTo("TRC20 / ERC20");
        assertThat(result.getData().stats().verified()).isEqualTo(0);
        assertThat(result.getData().rows()).hasSize(1);
        assertThat(result.getData().rows().get(0).pairedAddressMasked()).isEqualTo("未绑定");
        assertThat(result.getData().sources()).contains("nx_user.kyc_status");
        assertThat(userRepository.lastProfileRequest.pageNum()).isEqualTo(2);
        assertThat(userRepository.lastProfileRequest.pageSize()).isEqualTo(10);
    }

    @Test
    void kycOverviewSeedsC4RowsWhenLedgerDataIsMissing() {
        userRepository.kycSeedPresent = false;

        ApiResult<UserKycOverview> result = service.kycOverview(null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.kycSeedWrites).isEqualTo(1);
        assertThat(result.getData().rows())
                .extracting(UserKycLedgerRow::displayId)
                .contains("U00007704", "U00003188", "U00005501");
        assertThat(result.getData().rows())
                .anySatisfy(row -> {
                    assertThat(row.displayId()).isEqualTo("U00007704");
                    assertThat(row.backendStatus()).isEqualTo("PENDING");
                    assertThat(row.pairedAddressMasked()).isNotEqualTo("未绑定");
                    assertThat(row.network()).isEqualTo("TRC20");
                })
                .anySatisfy(row -> {
                    assertThat(row.displayId()).isEqualTo("U00005501");
                    assertThat(row.backendStatus()).isEqualTo("NONE");
                    assertThat(row.pairedAddressMasked()).isEqualTo("未绑定");
                });
    }

    @Test
    void kycStatusChangeRequiresIdempotencyKey() {
        ApiResult<UserKycLedgerRow> result = service.updateKycStatus(
                1L,
                null,
                new UserKycStatusUpdateRequest("APPROVED", "offline verification passed", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void kycStatusChangeRejectsUnsupportedStatusWith422() {
        ApiResult<UserKycLedgerRow> result = service.updateKycStatus(
                1L,
                "idem-c4-kyc",
                new UserKycStatusUpdateRequest("MANUAL_JSON", "offline verification passed", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("KYC_STATUS_UNSUPPORTED");
    }

    @Test
    void kycApproveBelowB1RedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<UserKycLedgerRow> result = service.updateKycStatus(
                1L,
                "idem-c4-kyc",
                new UserKycStatusUpdateRequest("APPROVED", "offline verification passed", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void kycStatusChangeUpdatesRepositoryAndWritesAudit() {
        ApiResult<UserKycLedgerRow> result = service.updateKycStatus(
                1L,
                "idem-c4-kyc",
                new UserKycStatusUpdateRequest("APPROVED", "offline verification passed", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().backendStatus()).isEqualTo("APPROVED");
        assertThat(userRepository.user.kycStatus()).isEqualTo("APPROVED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_STATUS_CHANGED");
    }

    @Test
    void kycNetworkWhitelistRejectsRawJsonAndUrl() {
        ApiResult<Map<String, Object>> rawJson = service.updateKycNetworkWhitelist(
                "idem-c4-network",
                new UserKycNetworkUpdateRequest("{\"net\":\"TRC20\"}", "network policy cleanup", "superadmin"));
        ApiResult<Map<String, Object>> url = service.updateKycNetworkWhitelist(
                "idem-c4-network-2",
                new UserKycNetworkUpdateRequest("https://example.com/list", "network policy cleanup", "superadmin"));

        assertThat(rawJson.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(url.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void kycNetworkWhitelistPersistsConfigAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateKycNetworkWhitelist(
                "idem-c4-network",
                new UserKycNetworkUpdateRequest("TRC20 / ERC20 / BTC", "network policy cleanup", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("value", "TRC20 / ERC20 / BTC");
        assertThat(configFacade.values).containsEntry("kyc.network_whitelist", "TRC20 / ERC20 / BTC");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_NETWORK_WHITELIST_UPDATED");
    }

    @Test
    void kycMaskedExportCreatesServerJobAndAudit() {
        ApiResult<Map<String, Object>> result = service.createKycExport(
                "idem-c4-export",
                new UserKycExportRequest("MASKED_LEDGER", "quarterly regulatory package", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "QUEUED").containsEntry("masked", true);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_MASKED_EXPORT_CREATED");
    }

    @Test
    void creditAssetAdjustmentBelowB1RedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.createAssetAdjustment(
                1L,
                "idem-c4",
                new UserAssetAdjustmentRequest("NEX", "CREDIT", "10", "manual compensation", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void assetAdjustmentOverviewSeedsC3RowsWhenMissing() {
        ApiResult<Map<String, Object>> result = service.assetAdjustmentOverview();

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.assetAdjustmentSeedWrites).isEqualTo(1);
        assertThat(result.getData())
                .containsEntry("pending", 3L)
                .containsEntry("approved", 3L)
                .containsEntry("suspended", 1L);
    }

    @Test
    void assetAdjustmentsReturnsServerPageAndDetail() {
        ApiResult<Map<String, Object>> created = service.createAssetAdjustment(
                1L,
                "idem-c3-create",
                new UserAssetAdjustmentRequest("NEX", "DEBIT", "3.5", "manual correction after support ticket", "superadmin"));

        ApiResult<PageResult<UserAssetAdjustmentView>> page = service.assetAdjustments(
                new UserAssetAdjustmentQueryRequest("PENDING_REVIEW", "NEX", 1L, null, 1, 20, null));
        ApiResult<UserAssetAdjustmentDetail> detail = service.assetAdjustmentDetail(String.valueOf(created.getData().get("adjustmentNo")));

        assertThat(page.getCode()).isZero();
        assertThat(page.getData().getTotal()).isEqualTo(1);
        assertThat(page.getData().getRecords().get(0).asset()).isEqualTo("NEX");
        assertThat(detail.getCode()).isZero();
        assertThat(detail.getData().sources()).contains("nx_wallet_asset_adjustment");
        assertThat(detail.getData().user().userNo()).isEqualTo("U00000001");
    }

    @Test
    void assetAdjustmentsHistoryOnlyReturnsTerminalRows() {
        service.createAssetAdjustment(
                1L,
                "idem-c3-pending",
                new UserAssetAdjustmentRequest("NEX", "DEBIT", "3.5", "pending correction", "superadmin"));
        String approvedNo = String.valueOf(service.createAssetAdjustment(
                        1L,
                        "idem-c3-terminal",
                        new UserAssetAdjustmentRequest("NEX", "DEBIT", "2", "approved correction", "superadmin"))
                .getData()
                .get("adjustmentNo"));
        service.approveAssetAdjustment(
                approvedNo,
                "idem-c3-terminal-approve",
                new UserAssetAdjustmentReviewRequest("reviewed support evidence", "checker"));

        ApiResult<PageResult<UserAssetAdjustmentView>> result = service.assetAdjustments(
                new UserAssetAdjustmentQueryRequest(null, "NEX", null, null, 1, 20, true));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords())
                .extracting(UserAssetAdjustmentView::adjustmentNo)
                .contains(approvedNo);
        assertThat(userRepository.lastAssetAdjustmentRequest.historyOnly()).isTrue();
    }

    @Test
    void approvingAssetAdjustmentWritesAuditAndStatus() {
        String adjustmentNo = String.valueOf(service.createAssetAdjustment(
                1L,
                "idem-c3-create",
                new UserAssetAdjustmentRequest("NEX", "DEBIT", "3.5", "manual correction after support ticket", "superadmin"))
                .getData()
                .get("adjustmentNo"));

        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-approve",
                new UserAssetAdjustmentReviewRequest("reviewed ticket and ledger evidence", "checker"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().adjustment().status()).isEqualTo("APPROVED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getAction()).isEqualTo("C3_ASSET_ADJUSTMENT_APPROVED");
    }

    @Test
    void approvingNonPendingAdjustmentReturns409() {
        String adjustmentNo = String.valueOf(service.createAssetAdjustment(
                1L,
                "idem-c3-create",
                new UserAssetAdjustmentRequest("NEX", "DEBIT", "3.5", "manual correction after support ticket", "superadmin"))
                .getData()
                .get("adjustmentNo"));
        service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-approve",
                new UserAssetAdjustmentReviewRequest("reviewed ticket and ledger evidence", "checker"));

        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-approve-again",
                new UserAssetAdjustmentReviewRequest("second approval should be rejected", "checker"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void creditApprovalBelowRedlineSuspendsAndReturns422() {
        String adjustmentNo = String.valueOf(service.createAssetAdjustment(
                1L,
                "idem-c3-create",
                new UserAssetAdjustmentRequest("NEX", "CREDIT", "3.5", "manual compensation after support ticket", "superadmin"))
                .getData()
                .get("adjustmentNo"));
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-approve",
                new UserAssetAdjustmentReviewRequest("reviewed ticket and ledger evidence", "checker"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(userRepository.adjustments.get(adjustmentNo).status()).isEqualTo("SUSPENDED");
    }

    @Test
    void rejectingAssetAdjustmentWritesAuditAndStatus() {
        String adjustmentNo = String.valueOf(service.createAssetAdjustment(
                1L,
                "idem-c3-create",
                new UserAssetAdjustmentRequest("NEX", "CREDIT", "3.5", "manual compensation after support ticket", "superadmin"))
                .getData()
                .get("adjustmentNo"));

        ApiResult<UserAssetAdjustmentDetail> result = service.rejectAssetAdjustment(
                adjustmentNo,
                "idem-c3-reject",
                new UserAssetAdjustmentReviewRequest("evidence does not match wallet ledger", "checker"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().adjustment().status()).isEqualTo("REJECTED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getAction()).isEqualTo("C3_ASSET_ADJUSTMENT_REJECTED");
    }

    @Test
    void revokingAlreadyRevokedSessionReturns409() {
        userRepository.sessions.put("rt-revoked", new UserSessionView(
                1L, "rt-revoked", "ios", "10.0.0.*", "REVOKED", LocalDateTime.now(), LocalDateTime.now().plusDays(1), LocalDateTime.now()));

        ApiResult<UserSessionView> result = service.revokeSession(
                "rt-revoked",
                "idem-c3",
                new UserSessionRevokeRequest("security cleanup", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void credentialParamUpdateWritesNxConfigItemAndAudit() {
        ApiResult<UserCredentialParamView> result = service.updateCredentialParam(
                "accessTtl",
                "idem-c5-param",
                new UserCredentialParamUpdateRequest("6 小时", "support desk session ttl change", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().key()).isEqualTo("accessTtl");
        assertThat(result.getData().value()).isEqualTo("6 小时");
        assertThat(configFacade.values).containsEntry("auth.session.access_ttl_hours", "6");
        assertThat(configFacade.lastConfigGroup).isEqualTo("auth");
        assertThat(configFacade.lastValueType).isEqualTo("NUMBER");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C5_CREDENTIAL_PARAM_UPDATED");
    }

    @Test
    void registrationRiskOverviewComesFromBackendConfigAndK1Guards() {
        configFacade.values.put("auth.risk.otp_max_24h", "4");
        configFacade.values.put("auth.risk.captcha_off_window", "2h 后自动恢复");

        ApiResult<UserRegistrationRiskOverview> result = service.registrationRiskOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().stats().captchaTemporarilyDisabled()).isTrue();
        assertThat(result.getData().params()).extracting(UserRegistrationRiskParamView::key)
                .contains("otpTtl", "otpCooldown", "otpMax24h", "lockShort", "lockLong");
        assertThat(result.getData().params()).filteredOn(param -> "otpMax24h".equals(param.key()))
                .singleElement()
                .extracting(UserRegistrationRiskParamView::value)
                .isEqualTo("4 次");
        assertThat(configFacade.values).containsEntry("auth.risk.otp_max_24h", "4");
        assertThat(result.getData().k1RejectCode()).isEqualTo("MULTI_ACCOUNT_PARAM_BELONGS_TO_K1");
        assertThat(result.getData().sources()).contains("nx_config_item:auth.risk.*");
    }

    @Test
    void registrationRiskOverviewSeedsMissingBackendConfigBeforeReading() {
        ApiResult<UserRegistrationRiskOverview> result = service.registrationRiskOverview();

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("auth.risk.otp_ttl_minutes", "5")
                .containsEntry("auth.risk.otp_cooldown_seconds", "60")
                .containsEntry("auth.risk.otp_max_24h", "3")
                .containsEntry("auth.risk.login_lock_threshold", "5")
                .containsEntry("auth.risk.lock_duration_minutes", "15")
                .containsEntry("auth.risk.login_long_lock_threshold", "10")
                .containsEntry("auth.risk.long_lock_duration_hours", "24")
                .containsEntry("auth.risk.otp_sent_today", "31240")
                .containsEntry("auth.risk.captcha_triggered_today", "412")
                .containsEntry("auth.risk.locked_short_count", "198")
                .containsEntry("auth.risk.locked_long_count", "16")
                .containsEntry("auth.risk.stuffing_clusters_7d", "38");
        assertThat(configFacade.values).doesNotContainKey("auth.risk.captcha_off_window");
        assertThat(configFacade.lastConfigGroup).isEqualTo("auth");
        assertThat(configFacade.lastValueType).isEqualTo("NUMBER");
        assertThat(result.getData().params()).filteredOn(param -> "lockShort".equals(param.key()))
                .singleElement()
                .extracting(UserRegistrationRiskParamView::value)
                .isEqualTo("5 次 / 15 分钟");
    }

    @Test
    void registrationRiskParamUpdateWritesConfigAndAudit() {
        ApiResult<UserRegistrationRiskParamView> result = service.updateRegistrationRiskParam(
                "lockShort",
                "idem-c6-lock",
                new UserRegistrationRiskParamUpdateRequest("6 次 / 20 分钟", "tighten login brute force guard", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("6 次 / 20 分钟");
        assertThat(configFacade.values).containsEntry("auth.risk.login_lock_threshold", "6");
        assertThat(configFacade.values).containsEntry("auth.risk.lock_duration_minutes", "20");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C6_REGISTRATION_RISK_PARAM_UPDATED");
    }

    @Test
    void registrationRiskRejectsK1OwnedParamsWith422() {
        ApiResult<UserRegistrationRiskParamView> result = service.updateRegistrationRiskParam(
                "maxSignupPerIp24h",
                "idem-c6-k1",
                new UserRegistrationRiskParamUpdateRequest("3", "wrong page should be rejected", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("MULTI_ACCOUNT_PARAM_BELONGS_TO_K1");
    }

    @Test
    void captchaOffWindowIsPersistedAndRejectsRawUrl() {
        ApiResult<UserRegistrationRiskParamView> disabled = service.updateRegistrationRiskParam(
                "captchaOff",
                "idem-c6-captcha",
                new UserRegistrationRiskParamUpdateRequest("2h 后自动恢复", "captcha provider outage window", "superadmin"));
        ApiResult<UserRegistrationRiskParamView> rawUrl = service.updateRegistrationRiskParam(
                "captchaOff",
                "idem-c6-captcha-url",
                new UserRegistrationRiskParamUpdateRequest("https://status.example.com", "captcha provider outage window", "superadmin"));

        assertThat(disabled.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("auth.risk.captcha_off_window", "2h 后自动恢复");
        assertThat(rawUrl.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void disablingTwoFactorRequiresIdempotencyAndUpdatesSecurity() {
        userRepository.twoFactorEnabled = true;

        ApiResult<UserSecurityStatusView> result = service.disableTwoFactor(
                1L,
                "idem-c5-2fa",
                new UserSecurityActionRequest("verified support ownership", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().twoFactorEnabled()).isFalse();
        assertThat(userRepository.twoFactorEnabled).isFalse();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C5_TWO_FACTOR_DISABLED");
    }

    @Test
    void passwordResetMarksResetRequiredAndRevokesActiveSessions() {
        userRepository.sessions.put("rt-active", new UserSessionView(
                1L, "rt-active", "web", "10.0.0.*", "ACTIVE", LocalDateTime.now(), LocalDateTime.now().plusDays(1), null));

        ApiResult<UserSecurityStatusView> result = service.requestPasswordReset(
                1L,
                "idem-c5-password",
                new UserSecurityActionRequest("verified support ownership", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().passwordResetRequired()).isTrue();
        assertThat(userRepository.passwordResetMarker).startsWith("RESET_REQUIRED$");
        assertThat(userRepository.sessions.get("rt-active").status()).isEqualTo("REVOKED");
    }

    @Test
    void unlockClearsLoginFailures() {
        userRepository.loginFailCount = 8;

        ApiResult<UserSecurityStatusView> result = service.unlockSecurity(
                1L,
                "idem-c5-unlock",
                new UserSecurityActionRequest("risk review passed", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().loginFailCount()).isZero();
        assertThat(result.getData().locked()).isFalse();
    }

    @Test
    void securityOverviewSeedsC5RowsWhenSecurityRowsAreMissing() {
        userRepository.sessions.clear();
        userRepository.c5SeedPresent = false;

        ApiResult<UserSecurityOverview> result = service.securityOverview("usr_2231", null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.securitySessionSeedWrites).isEqualTo(1);
        assertThat(result.getData().selectedUser().userNo()).isEqualTo("U00002231");
        assertThat(result.getData().sessions().getRecords()).extracting(UserSessionView::refreshTokenId)
                .contains("C5-SESSION-usr_2231-IOS");
        assertThat(result.getData().lockedUsers()).extracting(UserSecurityUserRow::userNo)
                .contains("U00008807", "U00003315");
        assertThat(result.getData().sources()).contains("nx_user_security", "nx_user_session");
    }

    @Test
    void securityOverviewReadsLockedUsersFromUserSecurityTable() {
        userRepository.upsertSecuritySessionSeeds();
        userRepository.omitC5UsersFromGenericSearch = true;

        ApiResult<UserSecurityOverview> result = service.securityOverview("U00002231", null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.lockedSecurityUsersCalls).isGreaterThan(0);
        assertThat(result.getData().lockedUsers()).extracting(UserSecurityUserRow::userNo)
                .containsExactly("U00003315", "U00008807");
    }

    private static final class FakeUserOpsRepository implements UserOpsRepository {
        private UserAccountView user = new UserAccountView(
                1L,
                "U00000001",
                "Alice",
                "138****8000",
                "86",
                "ACTIVE",
                "PENDING",
                "L1",
                "V1",
                true,
                new BigDecimal("10"),
                new BigDecimal("20"),
                36,
                "低风险",
                2L,
                1L,
                LocalDateTime.now().minusDays(10),
                LocalDateTime.now());
        private final Map<String, UserSessionView> sessions = new LinkedHashMap<>();
        private final Map<String, UserAssetAdjustmentView> adjustments = new LinkedHashMap<>();
        private final List<UserAccountListEntryView> accountLists = new ArrayList<>();
        private final List<UserImpersonationSessionView> impersonations = new ArrayList<>();
        private final Map<Long, UserAccountView> kycSeedUsers = new LinkedHashMap<>();
        private final Map<Long, String> walletAddresses = new LinkedHashMap<>();
        private UserAccountView c2SeedUser;
        private final Map<Long, UserAccountView> c5SeedUsers = new LinkedHashMap<>();
        private final Map<Long, UserSecurityStatusView> c5SecurityRows = new LinkedHashMap<>();
        private boolean c2SeedPresent = true;
        private boolean c5SeedPresent = true;
        private boolean omitC5UsersFromGenericSearch = false;
        private boolean kycSeedPresent = true;
        private int accountActionSeedWrites = 0;
        private int securitySessionSeedWrites = 0;
        private int lockedSecurityUsersCalls = 0;
        private int assetAdjustmentSeedWrites = 0;
        private int kycSeedWrites = 0;
        private boolean twoFactorEnabled = false;
        private int loginFailCount = 0;
        private String passwordResetMarker;
        private UserQueryRequest lastProfileRequest;
        private UserAssetAdjustmentQueryRequest lastAssetAdjustmentRequest;
        private Long lastSessionPageUserId;

        @Override
        public Map<String, Object> overview() {
            List<UserAccountView> accounts = allUsers();
            long frozenUsers = accounts.stream()
                    .filter(account -> "FROZEN".equals(account.status()))
                    .count();
            long activeUsers = accounts.stream()
                    .filter(account -> "ACTIVE".equals(account.status()))
                    .count();
            long activeSessions = sessions.values().stream()
                    .filter(session -> "ACTIVE".equals(session.status()))
                    .count();
            return new LinkedHashMap<>(Map.of(
                    "totalUsers", (long) accounts.size(),
                    "activeUsers", activeUsers,
                    "frozenUsers", frozenUsers,
                    "activeSessions", activeSessions));
        }

        @Override
        public List<UserAccountView> search(String keyword, String status, String kycStatus, int limit) {
            return filterUsers(status, kycStatus).stream().limit(limit).toList();
        }

        @Override
        public PageResult<UserAccountView> pageProfiles(UserQueryRequest request) {
            lastProfileRequest = request;
            int pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            int pageSize = request == null || request.pageSize() == null ? 20 : request.pageSize();
            List<UserAccountView> rows = filterUsers(
                    request == null ? null : request.status(),
                    request == null ? null : request.kycStatus());
            int from = Math.min((pageNum - 1) * pageSize, rows.size());
            if (from == rows.size() && !rows.isEmpty()) {
                from = 0;
            }
            int to = Math.min(from + pageSize, rows.size());
            return new PageResult<>(rows.size(), pageNum, pageSize, rows.subList(from, to));
        }

        @Override
        public long countByKycStatus(String kycStatus) {
            return allUsers().stream()
                    .filter(account -> kycStatus != null && kycStatus.equals(account.kycStatus()))
                    .count();
        }

        @Override
        public Optional<UserAccountView> findById(Long userId) {
            return allUsers().stream()
                    .filter(account -> userId != null && userId.equals(account.id()))
                    .findFirst();
        }

        @Override
        public Optional<Long> findUserIdByLookupKey(String lookupKey) {
            if (lookupKey == null) {
                return Optional.empty();
            }
            if ("usr_77D4".equalsIgnoreCase(lookupKey)) {
                return kycSeedPresent ? Optional.of(7704L) : Optional.empty();
            }
            if ("usr_2231".equalsIgnoreCase(lookupKey) || "U00002231".equalsIgnoreCase(lookupKey)) {
                return c5SeedPresent ? Optional.of(2231L) : Optional.empty();
            }
            if ("usr_8807".equalsIgnoreCase(lookupKey) || "U00008807".equalsIgnoreCase(lookupKey)) {
                return c5SeedPresent ? Optional.of(8807L) : Optional.empty();
            }
            if ("usr_3315".equalsIgnoreCase(lookupKey) || "U00003315".equalsIgnoreCase(lookupKey)) {
                return c5SeedPresent ? Optional.of(3315L) : Optional.empty();
            }
            if (lookupKey.startsWith("usr_")) {
                return c2SeedPresent ? Optional.of(8807L) : Optional.empty();
            }
            return lookupKey.equals(String.valueOf(user.id())) || lookupKey.equals(user.userNo())
                    ? Optional.of(user.id())
                    : Optional.empty();
        }

        @Override
        public void upsertUser360Seed(User360Seed seed) {
            // Not used by OpsUserService tests.
        }

        @Override
        public void upsertAccountActionSeeds() {
            accountActionSeedWrites++;
            c2SeedPresent = true;
            c2SeedUser = new UserAccountView(
                    8807L,
                    "U00008807",
                    "Marcus Ray",
                    "202****8807",
                    "1",
                    "RESTRICTED",
                    "PENDING",
                    "L3",
                    "V4",
                    true,
                    new BigDecimal("4080.50"),
                    new BigDecimal("186000.00"),
                    82,
                    "高风险",
                    5L,
                    3L,
                    LocalDateTime.now().minusDays(32),
                    LocalDateTime.now().minusMinutes(8));
            sessions.put("rt-c2", new UserSessionView(
                    8807L, "rt-c2", "Chrome Windows", "10.31.8.*", "ACTIVE",
                    LocalDateTime.now().minusMinutes(8), LocalDateTime.now().plusDays(7), null));
            accountLists.add(new UserAccountListEntryView(
                    8807L, "U00008807", "Marcus Ray", "ALLOW", "seeded c2 smoke row", "ACTIVE",
                    LocalDateTime.now().plusDays(30), "superadmin", LocalDateTime.now(), null, null, null));
            impersonations.add(new UserImpersonationSessionView(
                    "IMP-204", 8807L, "U00008807", "Marcus Ray", "ACTIVE", 30, "cs_amy",
                    "support troubleshooting", LocalDateTime.now().plusMinutes(14), LocalDateTime.now().minusMinutes(16),
                    null, null, null, 14L));
        }

        @Override
        public void upsertKycLedgerSeeds() {
            kycSeedWrites++;
            kycSeedPresent = true;
            kycSeedUsers.put(7704L, kycSeedUser(7704L, "U00007704", "Harper Stone", "202****7704", "US", "ACTIVE", "PENDING", 48));
            kycSeedUsers.put(3188L, kycSeedUser(3188L, "U00003188", "Ava Miller", "202****3188", "US", "ACTIVE", "APPROVED", 24));
            kycSeedUsers.put(2231L, kycSeedUser(2231L, "U00002231", "Sofia Park", "010****2231", "KR", "ACTIVE", "APPROVED", 35));
            kycSeedUsers.put(5501L, kycSeedUser(5501L, "U00005501", "Noah White", "071****5501", "UK", "ACTIVE", "NONE", 74));
            kycSeedUsers.put(9000L, kycSeedUser(9000L, "U00009000", "Mia Costa", "119****9000", "BR", "ACTIVE", "APPROVED", 31));
            walletAddresses.put(7704L, "TBn8SeedKycAddress000000000000000001p");
            walletAddresses.put(3188L, "TR7NSeedKycAddress00000000000000000f2");
            walletAddresses.put(2231L, "bc1qseedkycaddress0000000000000000007e");
            walletAddresses.remove(5501L);
            walletAddresses.put(9000L, "TQxmSeedKycAddress000000000000000009c");
        }

        @Override
        public void upsertAssetAdjustmentSeeds() {
            assetAdjustmentSeedWrites++;
            adjustments.putIfAbsent("ADJ-7741", adjustment(
                    "ADJ-7741", 1L, "USDT", "CREDIT", new BigDecimal("1200.00"),
                    "客服补偿 · 工单 #88213", "support_zhang", "PENDING_REVIEW", null, null));
            adjustments.putIfAbsent("ADJ-3188", adjustment(
                    "ADJ-3188", 1L, "USDT", "CREDIT", new BigDecimal("480.00"),
                    "活动补发 · 工单 #88106", "growth_lee", "PENDING_REVIEW", null, null));
            adjustments.putIfAbsent("ADJ-0029", adjustment(
                    "ADJ-0029", 1L, "USDT", "DEBIT", new BigDecimal("260.00"),
                    "系统纠错 · 重复入账红冲", "finance_lin", "PENDING_REVIEW", null, null));
            adjustments.putIfAbsent("ADJ-1182", adjustment(
                    "ADJ-1182", 1L, "USDT", "CREDIT", new BigDecimal("380.00"),
                    "客服补偿 · 覆盖率红线挂起", "support_amy", "SUSPENDED", "risklead_h", "覆盖率低于红线"));
            adjustments.putIfAbsent("ADJ-1183", adjustment(
                    "ADJ-1183", 1L, "USDT", "CREDIT", new BigDecimal("120.00"),
                    "客服补偿 · 充值延迟补偿", "support_amy", "APPROVED", "finance_wu", "凭证匹配"));
            adjustments.putIfAbsent("ADJ-1179", adjustment(
                    "ADJ-1179", 1L, "USDT", "DEBIT", new BigDecimal("35.00"),
                    "系统纠错 · 小额重复入账", "finance_lin", "APPROVED", "finance_wu", "账单核对通过"));
            adjustments.putIfAbsent("ADJ-1175", adjustment(
                    "ADJ-1175", 1L, "NEX", "CREDIT", new BigDecimal("1200.00"),
                    "活动补发 · NEX 奖励补发", "growth_lee", "APPROVED", "finance_wu", "活动名单复核通过"));
        }

        @Override
        public void upsertSecuritySessionSeeds() {
            securitySessionSeedWrites++;
            c5SeedPresent = true;
            c5SeedUsers.put(2231L, c5SeedUser(2231L, "U00002231", "Sofia Park", "010****2231", "82", "ACTIVE", true, 35));
            c5SeedUsers.put(8807L, c5SeedUser(8807L, "U00008807", "Marcus Ray", "202****8807", "1", "RESTRICTED", true, 82));
            c5SeedUsers.put(3315L, c5SeedUser(3315L, "U00003315", "Elena Novak", "015****3315", "49", "ACTIVE", false, 68));
            c5SecurityRows.put(2231L, new UserSecurityStatusView(2231L, true, 1, false, false, 0, 0));
            c5SecurityRows.put(8807L, new UserSecurityStatusView(8807L, true, 5, false, false, 0, 0));
            c5SecurityRows.put(3315L, new UserSecurityStatusView(3315L, false, 10, false, true, 0, 0));
            sessions.put("C5-SESSION-usr_2231-IOS", new UserSessionView(
                    2231L, "C5-SESSION-usr_2231-IOS", "iPhone 15 Pro", "10.22.3.*", "ACTIVE",
                    LocalDateTime.now().minusMinutes(4), LocalDateTime.now().plusDays(21), null));
            sessions.put("C5-SESSION-usr_2231-WEB", new UserSessionView(
                    2231L, "C5-SESSION-usr_2231-WEB", "Chrome macOS", "10.22.3.*", "ACTIVE",
                    LocalDateTime.now().minusMinutes(22), LocalDateTime.now().plusDays(14), null));
            sessions.put("C5-SESSION-usr_8807-WEB", new UserSessionView(
                    8807L, "C5-SESSION-usr_8807-WEB", "Chrome Windows", "10.31.8.*", "ACTIVE",
                    LocalDateTime.now().minusMinutes(8), LocalDateTime.now().plusDays(3), null));
            sessions.put("C5-SESSION-usr_3315-IOS", new UserSessionView(
                    3315L, "C5-SESSION-usr_3315-IOS", "iPhone 14", "10.33.1.*", "ACTIVE",
                    LocalDateTime.now().minusMinutes(36), LocalDateTime.now().plusDays(2), null));
        }

        @Override
        public Optional<UserSecurityStatusView> securityStatus(Long userId) {
            UserSecurityStatusView c5Status = c5SecurityRows.get(userId);
            if (c5Status != null) {
                return Optional.of(c5Status);
            }
            if (userId == null || !userId.equals(user.id())) {
                return Optional.empty();
            }
            return Optional.of(new UserSecurityStatusView(
                    userId,
                    twoFactorEnabled,
                    loginFailCount,
                    false,
                    passwordResetMarker != null,
                    0,
                    0));
        }

        @Override
        public List<UserSecurityUserRow> lockedSecurityUsers(
                int shortLockThreshold,
                int longLockThreshold,
                int shortLockMinutes,
                int longLockHours,
                int limit) {
            lockedSecurityUsersCalls++;
            return c5SeedUsers.values().stream()
                    .map(account -> {
                        UserSecurityStatusView status = c5SecurityRows.get(account.id());
                        if (status == null || status.loginFailCount() < shortLockThreshold) {
                            return null;
                        }
                        boolean longLock = status.loginFailCount() >= longLockThreshold;
                        return new UserSecurityUserRow(
                                account.id(),
                                account.userNo(),
                                account.nickname(),
                                status.twoFactorEnabled(),
                                status.loginFailCount(),
                                true,
                                status.passwordResetRequired(),
                                longLock ? "LONG" : "SHORT",
                                longLock ? longLockHours + " 小时长锁" : shortLockMinutes + " 分钟短锁",
                                longLock ? "连续失败达到长锁阈值" : "连续登录/两步验证失败达到短锁阈值",
                                longLock ? longLockHours + " 小时内" : shortLockMinutes + " 分钟内");
                    })
                    .filter(row -> row != null)
                    .sorted(Comparator.comparingInt(UserSecurityUserRow::loginFailCount).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<UserSessionView> sessions(Long userId, int limit) {
            return sessions.values().stream()
                    .filter(session -> userId == null || userId.equals(session.userId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize) {
            lastSessionPageUserId = userId;
            List<UserSessionView> rows = sessions.values().stream()
                    .filter(session -> userId == null || userId.equals(session.userId()))
                    .toList();
            return new PageResult<>(rows.size(), pageNum, pageSize, rows);
        }

        @Override
        public List<UserTeamMemberView> teamMembers(Long userId, int limit) {
            return List.of();
        }

        @Override
        public long countTeamMembers(Long userId) {
            return 0;
        }

        @Override
        public long countDirectTeamMembers(Long userId) {
            return 0;
        }

        @Override
        public BigDecimal sumTeamVolume(Long userId) {
            return BigDecimal.ZERO;
        }

        @Override
        public List<UserNotificationView> notifications(Long userId, int limit) {
            return List.of();
        }

        @Override
        public long countUnreadNotifications(Long userId) {
            return 0;
        }

        @Override
        public long countPendingNotifications(Long userId) {
            return 0;
        }

        @Override
        public long countFailedNotifications(Long userId) {
            return 0;
        }

        @Override
        public List<UserAccountListEntryView> accountLists(String status, int limit) {
            return accountLists.stream()
                    .filter(entry -> status == null || status.equals(entry.status()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<UserAccountListEntryView> findAccountList(Long userId) {
            return accountLists.stream()
                    .filter(entry -> entry.userId().equals(userId))
                    .findFirst();
        }

        @Override
        public void upsertAccountList(Long userId, String kind, String reason, String operator, LocalDateTime expiresAt) {
            removeAccountListInternal(userId);
            accountLists.add(new UserAccountListEntryView(
                    userId, "U00000001", "Alice", kind, reason, "ACTIVE", expiresAt, operator,
                    LocalDateTime.now(), null, null, null));
        }

        @Override
        public void removeAccountList(Long userId, String reason, String operator) {
            UserAccountListEntryView current = findAccountList(userId).orElseThrow();
            removeAccountListInternal(userId);
            accountLists.add(new UserAccountListEntryView(
                    current.userId(), current.userNo(), current.nickname(), current.kind(), current.reason(), "REMOVED",
                    current.expiresAt(), current.createdBy(), current.createdAt(), operator, reason, LocalDateTime.now()));
        }

        @Override
        public List<UserImpersonationSessionView> impersonations(int limit) {
            return impersonations.stream().limit(limit).toList();
        }

        @Override
        public Optional<UserImpersonationSessionView> findImpersonation(String sessionNo) {
            return impersonations.stream()
                    .filter(session -> session.sessionNo().equals(sessionNo))
                    .findFirst();
        }

        @Override
        public void terminateImpersonation(String sessionNo, String reason, String operator) {
            UserImpersonationSessionView current = findImpersonation(sessionNo).orElseThrow();
            impersonations.removeIf(session -> session.sessionNo().equals(sessionNo));
            impersonations.add(new UserImpersonationSessionView(
                    current.sessionNo(), current.userId(), current.userNo(), current.nickname(), "TERMINATED",
                    current.ttlMinutes(), current.operator(), current.reason(), current.expiresAt(), current.createdAt(),
                    LocalDateTime.now(), operator, reason, 0L));
        }

        @Override
        public void updateUserStatus(Long userId, String status, String reason) {
            user = new UserAccountView(
                    user.id(), user.userNo(), user.nickname(), user.phoneMasked(), user.countryCode(), status, user.kycStatus(),
                    user.userLevel(), user.vRank(), user.twoFactorEnabled(), user.walletUsdt(), user.walletNex(),
                    user.riskScore(), user.riskBand(), user.deviceCount(), user.activeDeviceCount(),
                    user.registeredAt(), user.lastLoginAt());
        }

        @Override
        public void updateKycStatus(Long userId, String kycStatus, String reason) {
            replaceUser(userId, account -> new UserAccountView(
                    account.id(), account.userNo(), account.nickname(), account.phoneMasked(), account.countryCode(), account.status(), kycStatus,
                    account.userLevel(), account.vRank(), account.twoFactorEnabled(), account.walletUsdt(), account.walletNex(),
                    account.riskScore(), account.riskBand(), account.deviceCount(), account.activeDeviceCount(),
                    account.registeredAt(), account.lastLoginAt()));
        }

        @Override
        public Optional<UserSessionView> findSession(String refreshTokenId) {
            return Optional.ofNullable(sessions.get(refreshTokenId));
        }

        @Override
        public void revokeSession(String refreshTokenId, String reason) {
            UserSessionView session = sessions.get(refreshTokenId);
            sessions.put(refreshTokenId, new UserSessionView(
                    session.userId(), session.refreshTokenId(), session.deviceName(), session.clientIpMasked(), "REVOKED",
                    session.issuedAt(), session.expiresAt(), LocalDateTime.now()));
        }

        @Override
        public void revokeUserSessions(Long userId, String reason) {
            sessions.replaceAll((id, session) -> userId.equals(session.userId())
                    ? new UserSessionView(
                            session.userId(),
                            session.refreshTokenId(),
                            session.deviceName(),
                            session.clientIpMasked(),
                            "REVOKED",
                            session.issuedAt(),
                            session.expiresAt(),
                            LocalDateTime.now())
                    : session);
        }

        @Override
        public void disableTwoFactor(Long userId) {
            UserSecurityStatusView c5Status = c5SecurityRows.get(userId);
            if (c5Status != null) {
                c5SecurityRows.put(userId, new UserSecurityStatusView(
                        userId, false, c5Status.loginFailCount(), false, c5Status.passwordResetRequired(), 0, 0));
                return;
            }
            twoFactorEnabled = false;
        }

        @Override
        public void markPasswordResetRequired(Long userId, String resetMarker) {
            UserSecurityStatusView c5Status = c5SecurityRows.get(userId);
            if (c5Status != null) {
                c5SecurityRows.put(userId, new UserSecurityStatusView(
                        userId, c5Status.twoFactorEnabled(), c5Status.loginFailCount(), false, true, 0, 0));
                return;
            }
            passwordResetMarker = resetMarker;
        }

        @Override
        public void resetLoginFailures(Long userId) {
            UserSecurityStatusView c5Status = c5SecurityRows.get(userId);
            if (c5Status != null) {
                c5SecurityRows.put(userId, new UserSecurityStatusView(
                        userId, c5Status.twoFactorEnabled(), 0, false, c5Status.passwordResetRequired(), 0, 0));
                return;
            }
            loginFailCount = 0;
        }

        @Override
        public void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt) {
            impersonations.add(new UserImpersonationSessionView(
                    sessionNo, userId, "U00000001", "Alice", "ACTIVE", ttlMinutes, operator, reason, expiresAt,
                    LocalDateTime.now(), null, null, null, (long) ttlMinutes));
        }

        @Override
        public void createAssetAdjustment(String adjustmentNo, Long userId, String asset, String direction, BigDecimal amount, String reason, String operator) {
            adjustments.put(adjustmentNo, adjustment(adjustmentNo, userId, asset, direction, amount, reason, operator, "PENDING_REVIEW", null, null));
        }

        @Override
        public PageResult<UserAssetAdjustmentView> pageAssetAdjustments(UserAssetAdjustmentQueryRequest request) {
            lastAssetAdjustmentRequest = request;
            List<UserAssetAdjustmentView> records = adjustments.values().stream()
                    .filter(adjustment -> request == null || request.status() == null || request.status().equals(adjustment.status()))
                    .filter(adjustment -> request == null || !Boolean.TRUE.equals(request.historyOnly())
                            || !List.of("PENDING", "PENDING_REVIEW", "SUSPENDED").contains(adjustment.status()))
                    .filter(adjustment -> request == null || request.asset() == null || request.asset().equals(adjustment.asset()))
                    .filter(adjustment -> request == null || request.userId() == null || request.userId().equals(adjustment.userId()))
                    .toList();
            int pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            int pageSize = request == null || request.pageSize() == null ? 20 : request.pageSize();
            return new PageResult<>(records.size(), pageNum, pageSize, records);
        }

        @Override
        public Optional<UserAssetAdjustmentView> findAssetAdjustment(String adjustmentNo) {
            return Optional.ofNullable(adjustments.get(adjustmentNo));
        }

        @Override
        public void reviewAssetAdjustment(String adjustmentNo, String status, String checker, String reason) {
            UserAssetAdjustmentView before = adjustments.get(adjustmentNo);
            adjustments.put(adjustmentNo, adjustment(
                    before.adjustmentNo(),
                    before.userId(),
                    before.asset(),
                    before.direction(),
                    before.amount(),
                    before.reason(),
                    before.maker(),
                    status,
                    checker,
                    reason));
        }

        @Override
        public Optional<String> findWalletAddressByUserId(Long userId) {
            return Optional.ofNullable(walletAddresses.get(userId));
        }

        private List<UserAccountView> allUsers() {
            List<UserAccountView> accounts = new ArrayList<>();
            accounts.add(user);
            if (c2SeedUser != null) {
                accounts.add(c2SeedUser);
            }
            accounts.addAll(c5SeedUsers.values());
            accounts.addAll(kycSeedUsers.values());
            return accounts;
        }

        private List<UserAccountView> filterUsers(String status, String kycStatus) {
            return allUsers().stream()
                    .filter(account -> !omitC5UsersFromGenericSearch || !c5SeedUsers.containsKey(account.id()))
                    .filter(account -> kycStatus == null || kycStatus.equals(account.kycStatus()))
                    .toList();
        }

        private UserAccountView kycSeedUser(
                Long id,
                String userNo,
                String nickname,
                String phoneMasked,
                String countryCode,
                String status,
                String kycStatus,
                int riskScore) {
            return new UserAccountView(
                    id,
                    userNo,
                    nickname,
                    phoneMasked,
                    countryCode,
                    status,
                    kycStatus,
                    "L2",
                    "V3",
                    true,
                    new BigDecimal("120.00"),
                    new BigDecimal("6800.00"),
                    riskScore,
                    riskScore >= 70 ? "高风险" : "中风险",
                    2L,
                    1L,
                    LocalDateTime.now().minusDays(20),
                    LocalDateTime.now().minusMinutes(30));
        }

        private UserAccountView c5SeedUser(
                Long id,
                String userNo,
                String nickname,
                String phoneMasked,
                String countryCode,
                String status,
                boolean twoFactorEnabled,
                int riskScore) {
            return new UserAccountView(
                    id,
                    userNo,
                    nickname,
                    phoneMasked,
                    countryCode,
                    status,
                    "APPROVED",
                    "L3",
                    "V5",
                    twoFactorEnabled,
                    new BigDecimal("760.20"),
                    new BigDecimal("34000.00"),
                    riskScore,
                    riskScore >= 70 ? "高风险" : "中风险",
                    3L,
                    2L,
                    LocalDateTime.now().minusDays(28),
                    LocalDateTime.now().minusMinutes(6));
        }

        private void replaceUser(Long userId, java.util.function.Function<UserAccountView, UserAccountView> mapper) {
            if (userId == null) {
                return;
            }
            if (userId.equals(user.id())) {
                user = mapper.apply(user);
                return;
            }
            if (c2SeedUser != null && userId.equals(c2SeedUser.id())) {
                c2SeedUser = mapper.apply(c2SeedUser);
                return;
            }
            UserAccountView seed = kycSeedUsers.get(userId);
            if (seed != null) {
                kycSeedUsers.put(userId, mapper.apply(seed));
            }
        }

        private UserAssetAdjustmentView adjustment(
                String adjustmentNo,
                Long userId,
                String asset,
                String direction,
                BigDecimal amount,
                String reason,
                String maker,
                String status,
                String checker,
                String reviewReason) {
            return new UserAssetAdjustmentView(
                    adjustmentNo,
                    userId,
                    user.userNo(),
                    user.nickname(),
                    asset,
                    direction,
                    amount,
                    ("CREDIT".equals(direction) ? "+" : "-") + amount + ("USDT".equals(asset) ? " USDT" : " NEX"),
                    "OPS_USER_ADJUSTMENT",
                    reason,
                    maker,
                    checker,
                    status,
                    status,
                    "APPROVED".equals(status) ? "ok" : "REJECTED".equals(status) || "SUSPENDED".equals(status) ? "bad" : "warn",
                    "CREDIT".equals(direction),
                    false,
                    null,
                    "D4 待过账",
                    reviewReason,
                    checker == null ? null : LocalDateTime.now(),
                    LocalDateTime.now().minusMinutes(5),
                    LocalDateTime.now());
        }

        private void removeAccountListInternal(Long userId) {
            accountLists.removeIf(entry -> entry.userId().equals(userId));
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();
        private String lastConfigGroup;
        private String lastValueType;

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
            lastValueType = valueType;
            lastConfigGroup = configGroup;
        }
    }
}
