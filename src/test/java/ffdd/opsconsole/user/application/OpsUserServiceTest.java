package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.RiskUserStateFacade;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.bi.facade.BiKycRegulatoryExportFacade;
import ffdd.opsconsole.bi.facade.KycRegulatoryExportJob;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAccountControlFactView;
import ffdd.opsconsole.user.domain.UserAccountActionOverview;
import ffdd.opsconsole.user.domain.UserAccountActionContext;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentDetail;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserCredentialParamView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserKycLedgerRow;
import ffdd.opsconsole.user.domain.UserKycOverview;
import ffdd.opsconsole.user.domain.UserKycRecord;
import ffdd.opsconsole.user.domain.UserKycStatusHistoryView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserProfileExportFile;
import ffdd.opsconsole.user.domain.UserRegistrationRiskOverview;
import ffdd.opsconsole.user.domain.UserRegistrationRiskParamView;
import ffdd.opsconsole.user.domain.UserReadonlyDeviceView;
import ffdd.opsconsole.user.domain.UserSecurityOverview;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSecurityUserRow;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
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
import ffdd.opsconsole.user.dto.UserKycReviewTriggerRequest;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsUserServiceTest {
    private final FakeUserOpsRepository userRepository = new FakeUserOpsRepository();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeFinanceWithdrawalControlFacade financeWithdrawalControlFacade = new FakeFinanceWithdrawalControlFacade();
    private final FakeRiskUserStateFacade riskUserStateFacade = new FakeRiskUserStateFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final AdminOperatorRoleResolver roleResolver = mock(AdminOperatorRoleResolver.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final RiskKycReviewFacade riskKycReviewFacade = mock(RiskKycReviewFacade.class);
    private final BiKycRegulatoryExportFacade biKycExportFacade = mock(BiKycRegulatoryExportFacade.class);
    private final ffdd.opsconsole.shared.security.JwtTokenProvider tokenProvider =
            mock(ffdd.opsconsole.shared.security.JwtTokenProvider.class);
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final OpsUserService service = new OpsUserService(
            userRepository,
            coverageFacade,
            configFacade,
            financeWithdrawalControlFacade,
            riskUserStateFacade,
            auditLogService,
            idempotencyService,
            roleResolver,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            lockMapper,
            outboxService,
            tokenProvider,
            riskKycReviewFacade,
            biKycExportFacade,
            Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void stubLockMapperNoActiveLock() {
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
        when(roleResolver.resolveCode()).thenReturn("SUPER_ADMIN");
        when(tokenProvider.createImpersonationToken(any(), anyString(), anyString(), anyInt()))
                .thenReturn("signed-readonly-token");
        when(idempotencyService.execute(anyString(), anyString(), anyString(), eq(UserProfileExportFile.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(idempotencyService.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(biKycExportFacade.recent(anyInt())).thenReturn(List.of());
        when(biKycExportFacade.create(anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> new KycRegulatoryExportJob(
                        invocation.getArgument(0), "READY", invocation.getArgument(1),
                        invocation.getArgument(2), true,
                        "/api/admin/users/kyc/exports/" + invocation.getArgument(0) + "/download",
                        LocalDateTime.now()));
    }

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
                new UserStatusUpdateRequest("FROZEN", "RISK_HIT", "risk hold", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void c2StatusChangeRejectsMissingFreezeReasonCode() {
        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                "idem-c2-reason-code",
                new UserStatusUpdateRequest("FROZEN", "risk hold requires a reason code", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("C2_FREEZE_REASON_CODE_REQUIRED");
    }

    @Test
    void c2StatusChangeOnlyAllowsActiveToFrozenAndFrozenToActive() {
        userRepository.user = new UserAccountView(
                1L, "U00000001", "Alice", "138****8000", "+86", "BANNED",
                "PENDING", "LV1", "V0", false, new BigDecimal("100"), new BigDecimal("50"),
                88, "HIGH", 2L, 1L, LocalDateTime.now().minusDays(100), LocalDateTime.now());

        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                "idem-c2-invalid-transition",
                new UserStatusUpdateRequest("ACTIVE", "manual recovery is not an unfreeze", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("C2_STATUS_TRANSITION_NOT_ALLOWED");
    }

    @Test
    void c2StatusChangeRejectsWhenAnotherTransactionWinsTheStateTransition() {
        userRepository.rejectNextStatusTransition = true;

        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                "idem-c2-concurrent-freeze",
                new UserStatusUpdateRequest("FROZEN", "RISK_HIT", "concurrent freeze must have one winner", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("C2_STATUS_CONCURRENTLY_CHANGED");
        assertThat(userRepository.user.status()).isEqualTo("ACTIVE");
    }

    @Test
    void c2CommandsRejectReasonsLongerThanTwoHundredCharacters() {
        ApiResult<Map<String, Object>> result = service.revokeUserSessions(
                1L,
                "idem-c2-long-reason",
                new UserSessionRevokeAllRequest("x".repeat(201), "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("C2_REASON_LENGTH_INVALID");
    }

    @Test
    void c2ImpersonationOnlyAcceptsProductTtlOptions() {
        ApiResult<Map<String, Object>> result = service.startImpersonation(
                1L,
                "idem-c2-imp-ttl",
                new UserImpersonationRequest(6, "support troubleshooting", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("IMPERSONATION_TTL_UNSUPPORTED");
    }

    @Test
    void statusChangeWritesAudit() {
        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                "idem-c2",
                new UserStatusUpdateRequest("FROZEN", "RISK_HIT", "risk hold", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("FROZEN");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C2_USER_STATUS_CHANGED");
    }

    @Test
    void freezingUserRevokesSessionsFreezesD2AndRecordsK4Signal() {
        userRepository.sessions.put("rt-c2-live", new UserSessionView(
                1L, "rt-c2-live", "web", "10.0.0.*", "ACTIVE",
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1), null));
        financeWithdrawalControlFacade.updatedCount = 2;

        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                "idem-c2-freeze-cascade",
                new UserStatusUpdateRequest("FROZEN", "RISK_HIT", "risk hold", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.sessions.get("rt-c2-live").status()).isEqualTo("REVOKED");
        assertThat(financeWithdrawalControlFacade.lastUserId).isEqualTo(1L);
        assertThat(financeWithdrawalControlFacade.lastReason).isEqualTo("risk hold");
        assertThat(riskUserStateFacade.lastUserNo).isEqualTo("U00000001");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getDetail()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) captor.getValue().getDetail();
        assertThat(detail)
                .containsEntry("withdrawalsFrozen", 2)
                .containsEntry("riskSignalRecorded", true);
    }

    @Test
    void profilePageReturnsServerCanonicalPagination() {
        ApiResult<PageResult<UserAccountView>> result = service.profilePage(
                UserQueryRequest.basic("Alice", "FROZEN,BANNED,RESTRICTED", "PENDING", 70, 2, 10, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        assertThat(result.getData().getPageSize()).isEqualTo(10);
        assertThat(result.getData().getRecords()).containsExactly(userRepository.user);
        assertThat(userRepository.lastProfileRequest.status()).isEqualTo("FROZEN,BANNED,RESTRICTED");
        assertThat(userRepository.lastProfileRequest.riskMin()).isEqualTo(70);
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("ADMIN.USER_PROFILE_SEARCHED");
        assertThat(audit.getValue().getDetail().toString()).contains("filterHash").doesNotContain("Alice");
    }

    @Test
    void profilePageRejectsRawPhoneKeywordBeforeRepositoryAccess() {
        ApiResult<PageResult<UserAccountView>> result = service.profilePage(
                UserQueryRequest.basic("13800138000", null, null, null, 1, 50, null));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("C1_RAW_PHONE_SEARCH_FORBIDDEN");
        assertThat(userRepository.lastProfileRequest).isNull();
    }

    @Test
    void profilePageRejectsOutOfRangePaginationBeforeRepositoryAccess() {
        ApiResult<PageResult<UserAccountView>> invalidPage = service.profilePage(
                UserQueryRequest.basic(null, null, null, null, 0, 20, null));
        ApiResult<PageResult<UserAccountView>> invalidPageSize = service.profilePage(
                UserQueryRequest.basic(null, null, null, null, 1, 201, null));

        assertThat(invalidPage.getCode()).isEqualTo(422);
        assertThat(invalidPage.getMessage()).isEqualTo("C1_PAGE_NUM_INVALID");
        assertThat(invalidPageSize.getCode()).isEqualTo(422);
        assertThat(invalidPageSize.getMessage()).isEqualTo("C1_PAGE_SIZE_INVALID");
        assertThat(userRepository.lastProfileRequest).isNull();
    }

    @Test
    void profilePageReadsExistingBusinessRowsWithoutCreatingFallbackData() {
        userRepository.loadAccountActionFixtures();

        ApiResult<PageResult<UserAccountView>> result = service.profilePage(
                UserQueryRequest.basic("Marcus", null, null, null, 1, 10, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords())
                .extracting(UserAccountView::userNo)
                .contains("U00008807");
        assertThat(userRepository.lastProfileRequest.keyword()).isEqualTo("Marcus");
    }

    @Test
    void profileExportUsesServerPaginationAndWritesAudit() {
        UserProfileExportFile file = service.exportProfileExcel(
                "idem-c1-export",
                UserProfileExportRequest.basic("Alice", "ACTIVE", null, 30, "C1 masked user export", "superadmin"));

        String workbook = new String(file.body(), StandardCharsets.UTF_8);
        assertThat(file.fileName()).startsWith("C1-USER-EXP-").endsWith(".csv");
        assertThat(workbook)
                .contains("用户编码")
                .contains("U00000001")
                .doesNotContain("userId")
                .doesNotContain("<th>ID</th>")
                .doesNotContain("13800138000");
        assertThat(userRepository.lastProfileRequest.status()).isEqualTo("ACTIVE");
        assertThat(userRepository.lastProfileRequest.riskMin()).isEqualTo(30);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("ADMIN.USER_LIST_EXPORTED");
        assertThat(captor.getValue().getDetail().toString()).contains("filterHash").doesNotContain("Alice");
        verify(idempotencyService).execute(
                eq("C1_USER_LIST_EXPORT"), eq("idem-c1-export"), anyString(), eq(UserProfileExportFile.class), any());
        verify(outboxService).publish(eq("USER_PROFILE_EXPORT"), anyString(), eq("ADMIN_USER_LIST_EXPORTED"), any());
    }

    @Test
    void concurrentProfileExportsReceiveDistinctCorrelationNumbers() {
        UserProfileExportRequest request = UserProfileExportRequest.basic(
                null, null, null, null, "C1 concurrent export", "superadmin");

        UserProfileExportFile first = service.exportProfileExcel("idem-c1-export-a", request);
        UserProfileExportFile second = service.exportProfileExcel("idem-c1-export-b", request);

        assertThat(first.fileName()).isNotEqualTo(second.fileName());
    }

    @Test
    void growthExportOmitsFinanceRiskAndDeviceColumns() {
        when(roleResolver.resolveCode()).thenReturn("GROWTH");

        UserProfileExportFile file = service.exportProfileExcel(
                "idem-c1-growth-export",
                UserProfileExportRequest.basic(null, null, null, null, "C1 growth export", "growth-user"));

        assertThat(new String(file.body(), StandardCharsets.UTF_8))
                .contains("用户编码", "生命周期", "V-Rank", "手机号(脱敏)")
                .doesNotContain("风险分", "风险等级", "设备数", "USDT余额", "NEX余额");
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
    void accountActionAccountResolvesAnExactUserOutsideTheOverviewPage() {
        ApiResult<UserAccountView> result = service.accountActionAccount("U00000001");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().userNo()).isEqualTo("U00000001");
    }

    @Test
    void accountActionAccountReturnsNotFoundInsteadOfSelectingAnotherUser() {
        ApiResult<UserAccountView> result = service.accountActionAccount("U99999999");

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void accountActionContextReturnsExactUserRelatedRowsOutsideOverviewCollections() {
        userRepository.loadAccountActionFixtures();

        ApiResult<UserAccountActionContext> result = service.accountActionContext("U00008807");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().account().userNo()).isEqualTo("U00008807");
        assertThat(result.getData().accountList().kind()).isEqualTo("ALLOW");
        assertThat(result.getData().sessions()).extracting(UserSessionView::userId).containsOnly(8807L);
        assertThat(result.getData().impersonations())
                .extracting(UserImpersonationSessionView::sessionNo).containsExactly("IMP-204");
        assertThat(result.getData().totalSessions()).isEqualTo(1);
        assertThat(result.getData().activeSessions()).isEqualTo(1);
        assertThat(result.getData().totalImpersonations()).isEqualTo(1);
        assertThat(result.getData().sessionsTruncated()).isFalse();
        assertThat(result.getData().impersonationsTruncated()).isFalse();
    }

    @Test
    void accountActionOverviewReadsExistingC2BusinessRows() {
        userRepository.loadAccountActionFixtures();

        ApiResult<UserAccountActionOverview> result = service.accountActionOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accounts()).extracting(UserAccountView::userNo).contains("U00008807");
        assertThat(result.getData().sessions()).extracting(UserSessionView::userId).contains(8807L);
        assertThat(result.getData().accountLists()).extracting(UserAccountListEntryView::kind).contains("ALLOW");
        assertThat(result.getData().impersonations()).extracting(UserImpersonationSessionView::sessionNo).contains("IMP-204");
    }

    @Test
    void accountActionOverviewDoesNotCreateRowsWhenBusinessRowsAreMissing() {
        userRepository.c2SeedPresent = false;

        ApiResult<UserAccountActionOverview> result = service.accountActionOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accounts()).extracting(UserAccountView::userNo).doesNotContain("U00008807");
        assertThat(result.getData().accountLists()).isEmpty();
        assertThat(result.getData().impersonations()).isEmpty();
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
    void accountListUpsertRejectsAnAlreadyExpiredActiveEntry() {
        ApiResult<UserAccountListEntryView> result = service.upsertAccountList(
                "idem-c2-expired-list",
                new UserAccountListUpsertRequest(1L, "BLOCK", "expired list must not become active",
                        "risklead_h", "2000-01-01"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("ACCOUNT_LIST_EXPIRES_AT_NOT_FUTURE");
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
        verify(auditLogService, org.mockito.Mockito.atLeast(2)).recordRequired(captor.capture());
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C2_USER_IMPERSONATION_TERMINATED");
    }

    @Test
    void terminatingImpersonationRejectsWhenAnotherTransactionWinsTheSessionTransition() {
        userRepository.impersonations.add(new UserImpersonationSessionView(
                "IMP-RACE", 1L, "U00000001", "Alice", "ACTIVE", 30, "cs_amy",
                "support troubleshooting", LocalDateTime.now().plusMinutes(20), LocalDateTime.now().minusMinutes(10),
                null, null, null, 20L));
        userRepository.rejectNextImpersonationTransition = true;

        ApiResult<UserImpersonationSessionView> result = service.terminateImpersonation(
                "IMP-RACE",
                "idem-c2-imp-race",
                new UserImpersonationTerminateRequest("concurrent termination must have one winner", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("IMPERSONATION_SESSION_CONCURRENTLY_CHANGED");
        assertThat(userRepository.findImpersonation("IMP-RACE").orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test
    void startingImpersonationWritesC2Audit() {
        ApiResult<Map<String, Object>> result = service.startImpersonation(
                1L,
                "idem-c2-imp-start",
                new UserImpersonationRequest(30, "USER_ISSUE_REPRO", "support troubleshooting", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "ACTIVE").containsEntry("ttlMinutes", 30);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C2_USER_IMPERSONATION_STARTED");
    }

    @Test
    void startingImpersonationRejectsASecondActiveSessionForTheSameUser() {
        userRepository.impersonations.add(new UserImpersonationSessionView(
                "IMP-ACTIVE", 1L, "U00000001", "Alice", "ACTIVE", 30, "support",
                "existing support session", LocalDateTime.now().plusMinutes(20), LocalDateTime.now().minusMinutes(10),
                null, null, null, 20L));

        ApiResult<Map<String, Object>> result = service.startImpersonation(
                1L,
                "idem-c2-second-active",
                new UserImpersonationRequest(15, "USER_ISSUE_REPRO", "second active session must be rejected", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("IMPERSONATION_SESSION_ALREADY_ACTIVE");
        assertThat(userRepository.impersonations).hasSize(1);
    }

    @Test
    void startingImpersonationRejectsTtlAboveTheThirtyMinutePrivacyLimit() {
        ApiResult<Map<String, Object>> result = service.startImpersonation(
                1L,
                "idem-c2-imp-too-long",
                new UserImpersonationRequest(31, "USER_ISSUE_REPRO", "oversized impersonation window", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("IMPERSONATION_TTL_UNSUPPORTED");
    }

    @Test
    void impersonationRendersFourDistinctServerAuthoritativeUserScreens() {
        userRepository.impersonations.add(new UserImpersonationSessionView(
                "IMP-VIEW", 1L, "U00000001", "Alice", "ACTIVE", 30, "support",
                "reproduce user issue", LocalDateTime.now().plusMinutes(20), LocalDateTime.now().minusMinutes(10),
                null, null, null, 20L));

        ApiResult<Map<String, Object>> home = service.impersonationReadonlyView(1L, "IMP-VIEW", "HOME");
        ApiResult<Map<String, Object>> wallet = service.impersonationReadonlyView(1L, "IMP-VIEW", "WALLET");
        ApiResult<Map<String, Object>> devices = service.impersonationReadonlyView(1L, "IMP-VIEW", "DEVICES");
        ApiResult<Map<String, Object>> profile = service.impersonationReadonlyView(1L, "IMP-VIEW", "PROFILE");

        assertThat(List.of(home, wallet, devices, profile)).allSatisfy(result -> {
            assertThat(result.getCode()).isZero();
            assertThat(result.getData()).containsEntry("claim", "impersonate_readonly")
                    .containsEntry("writePolicy", "DENY");
        });
        assertThat(screen(home)).containsEntry("template", "H5_HOME").containsKey("assetSummary");
        assertThat(screen(wallet)).containsEntry("template", "H5_WALLET").containsKey("assets");
        assertThat(screen(devices)).containsEntry("template", "H5_DEVICES").containsKey("devices");
        assertThat(screen(profile)).containsEntry("template", "H5_PROFILE").containsKey("activeSessions");
        assertThat(List.of(screen(home), screen(wallet), screen(devices), screen(profile)))
                .extracting(map -> map.get("template"))
                .doesNotHaveDuplicates();
    }

    @Test
    void impersonationPageAuditKeepsTheOriginatingAdminUnderTargetUserSecurityContext() {
        userRepository.impersonations.add(new UserImpersonationSessionView(
                "IMP-ACTOR", 1L, "U00000001", "Alice", "ACTIVE", 30, "superadmin",
                "reproduce user issue", LocalDateTime.now().plusMinutes(20), LocalDateTime.now().minusMinutes(10),
                null, null, null, 20L));
        UsernamePasswordAuthenticationToken impersonation = new UsernamePasswordAuthenticationToken(
                "1", null, List.of());
        impersonation.setDetails(Map.of("username", "U00000001", "subjectType", "IMPERSONATION"));
        SecurityContextHolder.getContext().setAuthentication(impersonation);

        try {
            ApiResult<Map<String, Object>> result = service.impersonationReadonlyView(1L, "IMP-ACTOR", "HOME");

            assertThat(result.getCode()).isZero();
            ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
            verify(auditLogService).recordRequiredForTrustedActor(audit.capture());
            assertThat(audit.getValue().getAction()).isEqualTo("C2_USER_IMPERSONATION_PAGE_VIEWED");
            assertThat(audit.getValue().getActorUsername()).isEqualTo("superadmin");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> screen(ApiResult<Map<String, Object>> result) {
        return (Map<String, Object>) result.getData().get("screen");
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
        assertThat(result.getData()).containsEntry("revokedCount", 1L);
        assertThat(userRepository.sessions.get("rt-active").status()).isEqualTo("REVOKED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
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

        ApiResult<UserKycOverview> result = service.kycOverview(null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().networkWhitelist()).isEqualTo("TRC20 / ERC20");
        assertThat(result.getData().stats().verified()).isEqualTo(0);
        assertThat(result.getData().rows()).hasSize(1);
        assertThat(result.getData().rows().get(0).pairedAddressMasked()).isEqualTo("未绑定");
        assertThat(result.getData().sources()).contains("KYC authority ledger");
    }

    @Test
    void kycOverviewReadsExistingC4BusinessRows() {
        userRepository.loadKycFixtures();

        ApiResult<UserKycOverview> result = service.kycOverview(null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().rows())
                .extracting(UserKycLedgerRow::displayId)
                .contains("U00007704", "U00003188", "U00005501");
        assertThat(result.getData().rows())
                .anySatisfy(row -> {
                    assertThat(row.displayId()).isEqualTo("U00007704");
                    assertThat(row.backendStatus()).isEqualTo("PENDING");
                    assertThat(row.pairedAddressMasked()).isNotEqualTo("未绑定");
                    assertThat(row.network()).isEqualTo("—");
                    assertThat(row.triggerSource()).isEqualTo("历史状态迁入");
                    assertThat(row.info())
                            .filteredOn(item -> "账户状态".equals(item.key()))
                            .extracting(item -> item.value())
                            .containsExactly("正常");
                })
                .anySatisfy(row -> {
                    assertThat(row.displayId()).isEqualTo("U00005501");
                    assertThat(row.backendStatus()).isEqualTo("NONE");
                    assertThat(row.pairedAddressMasked()).isEqualTo("未绑定");
                });
    }

    @Test
    void kycStatusChangeRequiresIdempotencyKey() {
        ApiResult<UserKycLedgerRow> result = service.verifyKyc(
                1L,
                null,
                new UserKycStatusUpdateRequest(
                        "APPROVED", "PENDING", "MANUAL_VERIFICATION", "offline verification passed",
                        "ticket:C4-001", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void kycStatusChangeRejectsUnsupportedReasonCodeWith422() {
        ApiResult<UserKycLedgerRow> result = service.verifyKyc(
                1L,
                "idem-c4-kyc",
                new UserKycStatusUpdateRequest(
                        "APPROVED", "PENDING", "MANUAL_JSON", "offline verification passed",
                        "ticket:C4-001", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("C4_REASON_CODE_UNSUPPORTED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_STATUS_CHANGE_REJECTED");
        assertThat(captor.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(captor.getValue().getDetail().toString()).contains("C4_REASON_CODE_UNSUPPORTED");
    }

    @Test
    void kycApproveBelowB1RedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<UserKycLedgerRow> result = service.verifyKyc(
                1L,
                "idem-c4-kyc",
                new UserKycStatusUpdateRequest(
                        "APPROVED", "PENDING", "MANUAL_VERIFICATION", "offline verification passed",
                        "ticket:C4-001", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getDetail().toString())
                .contains("COVERAGE_BELOW_REDLINE", "expectedState=PENDING", "currentState=PENDING", "nextState=APPROVED");
    }

    @Test
    void kycStaleExpectedStateIsRejectedAuditedWithoutChangingAuthority() {
        ApiResult<UserKycLedgerRow> result = service.revokeKyc(
                1L,
                "idem-c4-stale",
                new UserKycStatusUpdateRequest(
                        "NONE", "APPROVED", "COMPLIANCE_CORRECTION", "stale state must not overwrite authority",
                        "ticket:C4-STALE", "forged-operator"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("KYC_EXPECTED_STATE_MISMATCH");
        assertThat(userRepository.user.kycStatus()).isEqualTo("PENDING");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_STATUS_CHANGE_REJECTED");
        assertThat(captor.getValue().getDetail().toString())
                .contains("KYC_EXPECTED_STATE_MISMATCH", "expectedState=APPROVED", "currentState=PENDING", "nextState=NONE");
    }

    @Test
    void kycStatusChangeUpdatesRepositoryAndWritesAudit() {
        ApiResult<UserKycLedgerRow> result = service.verifyKyc(
                1L,
                "idem-c4-kyc",
                new UserKycStatusUpdateRequest(
                        "APPROVED", "PENDING", "MANUAL_VERIFICATION", "offline verification passed",
                        "ticket:C4-001", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().backendStatus()).isEqualTo("APPROVED");
        assertThat(userRepository.user.kycStatus()).isEqualTo("APPROVED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_STATUS_CHANGED");
        verify(outboxService).publish(eq("USER_KYC"), eq("1"), eq("admin.kyc_status_changed"), any());
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_NETWORK_WHITELIST_UPDATED");
    }

    @Test
    void kycNetworkValidationFailureWritesRequiredRejectedAudit() {
        ApiResult<Map<String, Object>> result = service.updateKycNetworkWhitelist(
                "idem-c4-network-invalid",
                new UserKycNetworkUpdateRequest("https://example.com/list", "network policy cleanup", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_NETWORK_WHITELIST_REJECTED");
        assertThat(captor.getValue().getResult()).isEqualTo("REJECTED");
    }

    @Test
    void kycMaskedExportCreatesServerJobAndAudit() {
        ApiResult<Map<String, Object>> result = service.createKycExport(
                "idem-c4-export",
                new UserKycExportRequest("MASKED_LEDGER", "quarterly regulatory package", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "READY").containsEntry("masked", true);
        assertThat(result.getData().get("downloadPath").toString()).contains("/kyc/exports/");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_MASKED_EXPORT_CREATED");
    }

    @Test
    void kycExportPersistenceFailureWritesRequiredFailureAudit() {
        when(biKycExportFacade.create(anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("storage unavailable"));

        assertThatThrownBy(() -> service.createKycExport(
                "idem-c4-export-failed",
                new UserKycExportRequest("MASKED_LEDGER", "quarterly regulatory package", "superadmin")))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_MASKED_EXPORT_FAILED");
        assertThat(captor.getValue().getResult()).isEqualTo("FAILED");
    }

    @Test
    void kycReviewTriggerCreatesK5TicketWithoutChangingKycStatus() {
        when(riskKycReviewFacade.triggerManualReview(anyString(), anyString(), anyString()))
                .thenReturn(new KycReviewTriggerResult(true, true, "KR-C4-ABC12345", "K5_MANUAL_REVIEW_CREATED"));

        ApiResult<Map<String, Object>> result = service.triggerKycReview(
                1L,
                "idem-c4-review",
                new ffdd.opsconsole.user.dto.UserKycReviewTriggerRequest(
                        "RISK_ESCALATION", "manual review requested from compliance desk",
                        "ticket:C4-002", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("ticketId", "KR-C4-ABC12345")
                .containsEntry("kycStatus", "PENDING");
        assertThat(userRepository.user.kycStatus()).isEqualTo("PENDING");
        verify(outboxService).publish(eq("RISK_KYC_REVIEW_TICKET"), eq("KR-C4-ABC12345"),
                eq("risk.kyc_review_triggered"), any());
    }

    @Test
    void kycReviewMergeConflictWritesRequiredFailureAudit() {
        when(riskKycReviewFacade.triggerManualReview(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("K5_REVIEW_MERGE_CONFLICT"));

        assertThatThrownBy(() -> service.triggerKycReview(
                1L,
                "idem-c4-review-conflict",
                new UserKycReviewTriggerRequest(
                        "RISK_ESCALATION", "manual review requested from compliance desk",
                        "ticket:C4-CONFLICT", "superadmin")))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_K5_REVIEW_TRIGGER_FAILED");
        assertThat(captor.getValue().getResult()).isEqualTo("FAILED");
    }

    @Test
    void missingKycExportDownloadWritesRequiredRejectedAudit() {
        when(biKycExportFacade.downloadCsv("KYC-EXP-ABCDEF123456")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadKycExport("KYC-EXP-ABCDEF123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("C4_EXPORT_JOB_NOT_FOUND");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C4_KYC_MASKED_EXPORT_DOWNLOAD_REJECTED");
        assertThat(captor.getValue().getResult()).isEqualTo("REJECTED");
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
    void c3AdjustmentExecutesImmediatelyAndPostsLedgerInOneCommand() {
        ApiResult<Map<String, Object>> result = service.createAssetAdjustment(
                1L,
                "idem-c3-immediate",
                c3Request("NEX", "DEBIT", "3.5", "SYSTEM_CORRECTION", "manual correction after support ticket", "ticket:C3-001"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("status", "APPROVED")
                .containsEntry("ledgerId", 9001L);
        assertThat(userRepository.postedLedgerBills).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("bizNo", result.getData().get("adjustmentNo")));
    }

    @Test
    void c3TinyNexAdjustmentRetainsUsdEquivalentPrecision() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(
                new BigDecimal("110.00"), new BigDecimal("85.00"), true,
                new BigDecimal("110000"), new BigDecimal("100000"), new BigDecimal("0.12"));

        ApiResult<Map<String, Object>> result = service.createAssetAdjustment(
                1L,
                "idem-c3-tiny-nex",
                c3Request("NEX", "DEBIT", "0.000001", "SYSTEM_CORRECTION", "verified tiny-amount precision correction", "ticket:C3-precision-001"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("amountUsd", new BigDecimal("0.00000012"));
    }

    @Test
    void c3AdjustmentUsesDurableIdempotencyScope() {
        service.createAssetAdjustment(
                1L,
                "idem-c3-replay",
                c3Request("USDT", "CREDIT", "5", "SUPPORT_COMPENSATION", "verified support compensation evidence", "ticket:C3-002"));

        verify(idempotencyService).execute(
                eq("C3_ASSET_ADJUSTMENT_CREATE"),
                eq("idem-c3-replay"),
                anyString(),
                eq(ApiResult.class),
                any());
    }

    @Test
    void c3AdjustmentRejectsRiskRoleAndAmountsAboveHardLimit() {
        when(roleResolver.resolveCode()).thenReturn("RISK");
        ApiResult<Map<String, Object>> forbidden = service.createAssetAdjustment(
                1L,
                "idem-c3-risk",
                c3Request("USDT", "CREDIT", "5", "SUPPORT_COMPENSATION", "verified support compensation evidence", "ticket:C3-003"));
        when(roleResolver.resolveCode()).thenReturn("SUPER_ADMIN");
        ApiResult<Map<String, Object>> excessive = service.createAssetAdjustment(
                1L,
                "idem-c3-limit",
                c3Request("USDT", "CREDIT", "10001", "SUPPORT_COMPENSATION", "verified support compensation evidence", "ticket:C3-004"));

        assertThat(forbidden.getCode()).isEqualTo(403);
        assertThat(excessive.getCode()).isEqualTo(400);
        assertThat(excessive.getMessage()).isEqualTo("C3_AMOUNT_EXCEEDS_LIMIT");
    }

    @Test
    void c3NexLimitUsesUsdEquivalentRatherThanTokenQuantity() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(
                new BigDecimal("110.00"), new BigDecimal("85.00"), true,
                new BigDecimal("110000"), new BigDecimal("100000"), new BigDecimal("0.12"));

        ApiResult<Map<String, Object>> result = service.createAssetAdjustment(
                1L,
                "idem-c3-nex-usd-limit",
                c3Request("NEX", "CREDIT", "10001", "SYSTEM_CORRECTION", "verified NEX correction below USD equivalent cap", "ticket:C3-limit-001"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("amountUsd", new BigDecimal("1200.12"));
    }

    @Test
    void c3SupportLargeAdjustmentCreatesRequestWithoutPostingBalance() {
        when(roleResolver.resolveCode()).thenReturn("SUPPORT");

        ApiResult<Map<String, Object>> direct = service.createAssetAdjustment(
                1L,
                "idem-c3-support-large-direct",
                c3Request("USDT", "CREDIT", "600", "SUPPORT_COMPENSATION", "verified large support compensation evidence", "ticket:C3-large-001"));
        ApiResult<Map<String, Object>> requested = service.requestLargeAssetAdjustment(
                1L,
                "idem-c3-support-large-request",
                c3Request("USDT", "CREDIT", "600", "SUPPORT_COMPENSATION", "verified large support compensation evidence", "ticket:C3-large-001"));

        assertThat(direct.getCode()).isEqualTo(403);
        assertThat(requested.getCode()).isZero();
        assertThat(requested.getData()).containsEntry("status", "PENDING_REVIEW");
        assertThat(userRepository.postedLedgerBills).isEmpty();
        assertThat(userRepository.adjustments.get(String.valueOf(requested.getData().get("requestNo"))).status())
                .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void c3FinanceLeadCanExecuteLargeRequestWhileRegularFinanceCannot() {
        when(roleResolver.resolveCode()).thenReturn("FINANCE");
        ApiResult<Map<String, Object>> finance = service.createAssetAdjustment(
                1L,
                "idem-c3-finance-large",
                c3Request("USDT", "DEBIT", "600", "SYSTEM_CORRECTION", "verified finance correction evidence", "ticket:C3-large-002"));
        when(roleResolver.resolveCode()).thenReturn("FINANCE_LEAD");
        ApiResult<Map<String, Object>> lead = service.createAssetAdjustment(
                1L,
                "idem-c3-finance-lead-large",
                c3Request("USDT", "CREDIT", "600", "SYSTEM_CORRECTION", "verified finance lead correction evidence", "ticket:C3-large-003"));

        assertThat(finance.getCode()).isEqualTo(403);
        assertThat(lead.getCode()).isZero();
        assertThat(lead.getData()).containsEntry("status", "APPROVED");
    }

    @Test
    void c3SuccessfulAdjustmentRequiresAuditAndEmitsBothCanonicalEvents() {
        ApiResult<Map<String, Object>> result = service.createAssetAdjustment(
                1L,
                "idem-c3-events",
                c3Request("USDT", "CREDIT", "5", "SUPPORT_COMPENSATION", "verified support compensation evidence", "ticket:C3-005"));

        assertThat(result.getCode()).isZero();
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
        verify(outboxService).publish(eq("USER_ASSET_ADJUSTMENT"), anyString(), eq("admin.balance_adjusted"), any(Map.class));
        verify(outboxService).publish(eq("WALLET_LEDGER"), anyString(), eq("admin.bill_adjusted"), any(Map.class));
    }

    @Test
    void assetAdjustmentOverviewReadsExistingBusinessRows() {
        userRepository.loadAssetAdjustmentFixtures();

        ApiResult<Map<String, Object>> result = service.assetAdjustmentOverview();

        assertThat(result.getCode()).isZero();
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
                new UserAssetAdjustmentQueryRequest("APPROVED", "NEX", 1L, null, 1, 20, null));
        ApiResult<UserAssetAdjustmentDetail> detail = service.assetAdjustmentDetail(String.valueOf(created.getData().get("adjustmentNo")));

        assertThat(page.getCode()).isZero();
        assertThat(page.getData().getTotal()).isEqualTo(1);
        assertThat(page.getData().getRecords().get(0).asset()).isEqualTo("NEX");
        assertThat(detail.getCode()).isZero();
        assertThat(detail.getData().sources()).contains("wallet asset adjustments");
        assertThat(detail.getData().user().userNo()).isEqualTo("U00000001");
    }

    @Test
    void assetAdjustmentStoresStructuredH7ReferenceInBusinessTableAndAudit() {
        ApiResult<Map<String, Object>> created = service.createAssetAdjustment(
                1L,
                "idem-c3-h7",
                new UserAssetAdjustmentRequest(
                        "NEX",
                        "DEBIT",
                        "3.5",
                        "manual compensation with voucher evidence",
                        "superadmin",
                        "H7_VOUCHER",
                        "voucher-agent-f"));

        String adjustmentNo = String.valueOf(created.getData().get("adjustmentNo"));
        ApiResult<UserAssetAdjustmentDetail> detail = service.assetAdjustmentDetail(adjustmentNo);

        assertThat(created.getCode()).isZero();
        assertThat(created.getData()).containsEntry("reasonCode", "OPS_USER_ADJUSTMENT");
        assertThat(detail.getData().adjustment().reasonCode()).isEqualTo("OPS_USER_ADJUSTMENT");
        assertThat(detail.getData().adjustment().evidenceRef()).isEqualTo("reference:voucher-agent-f");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat((Map<String, Object>) captor.getValue().getDetail())
                .containsEntry("reasonCode", "OPS_USER_ADJUSTMENT")
                .containsEntry("evidenceRef", "reference:voucher-agent-f");
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
        String adjustmentNo = userRepository.seedPendingAdjustment("NEX", "DEBIT", "3.5");

        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-approve",
                new UserAssetAdjustmentReviewRequest("reviewed ticket and ledger evidence", "checker"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().adjustment().status()).isEqualTo("APPROVED");
        assertThat(result.getData().adjustment().ledgerId()).isEqualTo(9001L);
        assertThat(result.getData().adjustment().sink()).isEqualTo("账本 #9001");
        assertThat(userRepository.postedLedgerBills).hasSize(1);
        assertThat(userRepository.postedLedgerBills.get(0))
                .containsEntry("bizNo", adjustmentNo)
                .containsEntry("bizType", "ADJUSTMENT")
                .containsEntry("userId", 1L)
                .containsEntry("asset", "NEX")
                .containsEntry("direction", "OUT")
                .containsEntry("status", "SUCCESS");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).recordRequired(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getAction()).isEqualTo("C3_ASSET_ADJUSTMENT_APPROVED");
        @SuppressWarnings("unchecked")
        Map<String, Object> auditDetail = (Map<String, Object>) captor.getAllValues().get(captor.getAllValues().size() - 1).getDetail();
        assertThat(auditDetail).containsEntry("ledgerId", 9001L);
        verify(idempotencyService).execute(
                eq("C3_ASSET_ADJUSTMENT_APPROVED"),
                eq("idem-c3-approve"),
                anyString(),
                eq(ApiResult.class),
                any());
    }

    @Test
    void makerCannotApproveOwnAssetAdjustment() {
        String adjustmentNo = userRepository.seedPendingAdjustment("NEX", "DEBIT", "3.5");

        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-self-approve",
                new UserAssetAdjustmentReviewRequest("maker must not approve the same request", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("C3_MAKER_CANNOT_REVIEW");
        assertThat(userRepository.adjustments.get(adjustmentNo).status()).isEqualTo("PENDING_REVIEW");
        assertThat(userRepository.postedLedgerBills).isEmpty();
    }

    @Test
    void reversingApprovedDebitRestoresOriginalBalanceEvenWhenCoverageIsBelowRedline() {
        String originalNo = "ADJ-ORIGINAL-DEBIT";
        userRepository.adjustments.put(originalNo, userRepository.adjustment(
                originalNo, 1L, "NEX", "DEBIT", new BigDecimal("0.000001"),
                "original debit accepted before coverage changed", "finance_maker", "APPROVED",
                "finance_checker", "original review completed", 8123L));
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(
                BigDecimal.ZERO, new BigDecimal("85.00"), true,
                BigDecimal.ZERO, new BigDecimal("100000"), BigDecimal.ZERO);

        ApiResult<Map<String, Object>> result = service.reverseAssetAdjustment(
                originalNo,
                "idem-c3-reverse-low-coverage",
                new UserAssetAdjustmentReviewRequest("restore the approved debit atomically", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("direction", "CREDIT")
                .containsEntry("reversalOf", originalNo)
                .containsEntry("status", "APPROVED");
        assertThat(userRepository.assetAdjustmentHasReversal(originalNo)).isTrue();
        assertThat(userRepository.postedLedgerBills).hasSize(1);
    }

    @Test
    void regularFinanceCannotApproveSupportLargeAdjustment() {
        String adjustmentNo = userRepository.seedPendingAdjustment("USDT", "CREDIT", "600");
        when(roleResolver.resolveCode()).thenReturn("FINANCE");

        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-finance-approve",
                new UserAssetAdjustmentReviewRequest("finance cannot approve this large request", "finance"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("C3_ADJUSTMENT_REVIEW_FORBIDDEN");
        assertThat(userRepository.adjustments.get(adjustmentNo).status()).isEqualTo("PENDING_REVIEW");
        assertThat(userRepository.postedLedgerBills).isEmpty();
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
    }

    @Test
    void approvingNonPendingAdjustmentReturns409() {
        String adjustmentNo = String.valueOf(service.createAssetAdjustment(
                1L,
                "idem-c3-create",
                new UserAssetAdjustmentRequest("NEX", "DEBIT", "3.5", "manual correction after support ticket", "superadmin"))
                .getData()
                .get("adjustmentNo"));
        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-approve-again",
                new UserAssetAdjustmentReviewRequest("second approval should be rejected", "checker"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void creditApprovalBelowRedlineRejectsAndReturns422() {
        String adjustmentNo = userRepository.seedPendingAdjustment("NEX", "CREDIT", "3.5");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(
                new BigDecimal("80.00"), new BigDecimal("85.00"), true,
                new BigDecimal("80000"), new BigDecimal("100000"), BigDecimal.ONE);

        ApiResult<UserAssetAdjustmentDetail> result = service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-approve",
                new UserAssetAdjustmentReviewRequest("reviewed ticket and ledger evidence", "checker"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(userRepository.adjustments.get(adjustmentNo).status()).isEqualTo("REJECTED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C3_ASSET_ADJUSTMENT_REJECTED_BY_COVERAGE");
        assertThat(captor.getValue().getResult()).isEqualTo("REJECTED");
    }

    @Test
    void approvalPostingFailureWritesRequiredFailureAuditInSeparateTransaction() {
        String adjustmentNo = userRepository.seedPendingAdjustment("USDT", "DEBIT", "600");
        userRepository.approvalFailure = new BizException(422, "C3_INSUFFICIENT_BALANCE");

        assertThatThrownBy(() -> service.approveAssetAdjustment(
                adjustmentNo,
                "idem-c3-insufficient-review",
                new UserAssetAdjustmentReviewRequest("verified request exceeds current wallet balance", "financelead")))
                .isInstanceOf(BizException.class)
                .hasMessage("C3_INSUFFICIENT_BALANCE");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C3_ASSET_ADJUSTMENT_REVIEW_FAILED");
        assertThat(captor.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(captor.getValue().getDetail().toString()).contains("C3_INSUFFICIENT_BALANCE");
    }

    @Test
    void rejectingAssetAdjustmentWritesAuditAndStatus() {
        String adjustmentNo = userRepository.seedPendingAdjustment("NEX", "CREDIT", "3.5");

        ApiResult<UserAssetAdjustmentDetail> result = service.rejectAssetAdjustment(
                adjustmentNo,
                "idem-c3-reject",
                new UserAssetAdjustmentReviewRequest("evidence does not match wallet ledger", "checker"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().adjustment().status()).isEqualTo("REJECTED");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).recordRequired(captor.capture());
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C5_CREDENTIAL_PARAM_UPDATED");
    }

    @Test
    void registrationRiskOverviewComesFromBackendConfigAndK1Guards() {
        configFacade.values.put("auth.risk.otp_max_24h", "4");
        configFacade.values.put("auth.risk.c6.version", "7");
        userRepository.registrationOtpToday = 12L;
        userRepository.registrationCaptchaToday = 3L;
        userRepository.registrationShortLocksToday = 2L;
        userRepository.registrationLongLocksToday = 1L;
        userRepository.registrationStuffingClusters7d = 4L;

        ApiResult<UserRegistrationRiskOverview> result = service.registrationRiskOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().configVersion()).isEqualTo(7L);
        assertThat(result.getData().stats().otpToday()).isEqualTo(12L);
        assertThat(result.getData().stats().captchaTriggeredToday()).isEqualTo(3L);
        assertThat(result.getData().stats().lockedShort()).isEqualTo(2L);
        assertThat(result.getData().stats().lockedLong()).isEqualTo(1L);
        assertThat(result.getData().stats().locked()).isEqualTo(3L);
        assertThat(result.getData().stats().stuffingClusters7d()).isEqualTo(4L);
        assertThat(result.getData().params()).extracting(UserRegistrationRiskParamView::key)
                .contains("otpTtl", "otpCooldown", "otpMax24h", "lockShort", "lockLong");
        assertThat(result.getData().params()).filteredOn(param -> "otpMax24h".equals(param.key()))
                .singleElement()
                .satisfies(param -> {
                    assertThat(param.value()).isEqualTo("4 次");
                    assertThat(param.readOnly()).isTrue();
                });
        assertThat(configFacade.values).containsEntry("auth.risk.otp_max_24h", "4");
        assertThat(result.getData().k1RejectCode()).isEqualTo("MULTI_ACCOUNT_PARAM_BELONGS_TO_K1");
        assertThat(result.getData().sources())
                .contains("nx_user_otp_challenge", "nx_event_outbox:auth.login_locked", "nx_admin_risk_multi_account_cluster");
    }

    @Test
    void registrationRiskOverviewUsesDocumentedFallbacksWhenBackendConfigIsMissing() {
        ApiResult<UserRegistrationRiskOverview> result = service.registrationRiskOverview();

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).isEmpty();
        assertThat(configFacade.values).doesNotContainKey("auth.risk.captcha_off_window");
        assertThat(result.getData().params()).filteredOn(param -> "lockShort".equals(param.key()))
                .singleElement()
                .extracting(UserRegistrationRiskParamView::value)
                .isEqualTo("5 次 / 15 分钟");
    }

    @Test
    void registrationRiskParamUpdateWritesConfigAndAudit() {
        configFacade.values.put("auth.risk.c6.version", "0");
        ApiResult<UserRegistrationRiskParamView> result = service.updateRegistrationRiskParam(
                "lockShort",
                "idem-c6-lock",
                new UserRegistrationRiskParamUpdateRequest("6 次 / 20 分钟", "tighten login brute force guard", "superadmin", 0L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("6 次 / 20 分钟");
        assertThat(configFacade.values).containsEntry("auth.risk.login_lock_threshold", "6");
        assertThat(configFacade.values).containsEntry("auth.risk.lock_duration_minutes", "20");
        assertThat(configFacade.values).containsEntry("auth.risk.c6.version", "1");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C6_REGISTRATION_RISK_PARAM_UPDATED");
        verify(idempotencyService).execute(eq("C6_REGISTRATION_RISK_PARAM:lockShort"), eq("idem-c6-lock"), anyString(), eq(ApiResult.class), any());
    }

    @Test
    void registrationRiskParamRejectsStaleVersionBeforeWriting() {
        configFacade.values.put("auth.risk.c6.version", "4");

        ApiResult<UserRegistrationRiskParamView> result = service.updateRegistrationRiskParam(
                "lockShort",
                "idem-c6-stale",
                new UserRegistrationRiskParamUpdateRequest("6 次 / 20 分钟", "stale operator screen", "superadmin", 3L));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("C6_CONFIG_VERSION_CONFLICT");
        assertThat(configFacade.values).doesNotContainKey("auth.risk.login_lock_threshold");
    }

    @Test
    void registrationRiskParamRequiresNonNegativeExpectedVersion() {
        ApiResult<UserRegistrationRiskParamView> missing = service.updateRegistrationRiskParam(
                "lockShort",
                "idem-c6-missing-version",
                new UserRegistrationRiskParamUpdateRequest("6 次 / 20 分钟", "missing optimistic version", "superadmin", null));
        ApiResult<UserRegistrationRiskParamView> negative = service.updateRegistrationRiskParam(
                "lockShort",
                "idem-c6-negative-version",
                new UserRegistrationRiskParamUpdateRequest("6 次 / 20 分钟", "invalid optimistic version", "superadmin", -1L));

        assertThat(missing.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(missing.getMessage()).isEqualTo("C6_EXPECTED_VERSION_REQUIRED");
        assertThat(negative.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(configFacade.values).doesNotContainKey("auth.risk.login_lock_threshold");
    }

    @Test
    void registrationRiskLongThresholdMustStayAboveShortThreshold() {
        configFacade.values.put("auth.risk.c6.version", "0");
        configFacade.values.put("auth.risk.login_lock_threshold", "5");

        ApiResult<UserRegistrationRiskParamView> result = service.updateRegistrationRiskParam(
                "lockLong",
                "idem-c6-interlock",
                new UserRegistrationRiskParamUpdateRequest("5 次 / 24 小时", "unsafe equal thresholds", "superadmin", 0L));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("C6_LONG_THRESHOLD_MUST_EXCEED_SHORT_THRESHOLD");
        assertThat(configFacade.values).doesNotContainKey("auth.risk.login_long_lock_threshold");
    }

    @Test
    void registrationRiskCompositeValueRejectsNegativeOrExtraNumbers() {
        configFacade.values.put("auth.risk.c6.version", "0");

        ApiResult<UserRegistrationRiskParamView> negative = service.updateRegistrationRiskParam(
                "lockShort",
                "idem-c6-negative",
                new UserRegistrationRiskParamUpdateRequest("-6 次 / 20 分钟", "reject ambiguous negative value", "superadmin", 0L));
        ApiResult<UserRegistrationRiskParamView> extra = service.updateRegistrationRiskParam(
                "lockShort",
                "idem-c6-extra",
                new UserRegistrationRiskParamUpdateRequest("6 次 / 20 分钟 / 999", "reject hidden extra value", "superadmin", 0L));

        assertThat(negative.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(extra.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(configFacade.values).doesNotContainKeys(
                "auth.risk.login_lock_threshold", "auth.risk.lock_duration_minutes");
    }

    @Test
    void registrationRiskRejectsOtpWritesBecauseK2IsCanonicalOwner() {
        ApiResult<UserRegistrationRiskParamView> result = service.updateRegistrationRiskParam(
                "otpCooldown",
                "idem-c6-otp",
                new UserRegistrationRiskParamUpdateRequest("90 秒", "wrong page should be rejected", "superadmin", 0L));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("OTP_CONFIG_BELONGS_TO_K2");
        assertThat(configFacade.values).doesNotContainKey("auth.risk.otp_cooldown_seconds");
    }

    @Test
    void registrationRiskRejectsK1OwnedParamsWith422() {
        ApiResult<UserRegistrationRiskParamView> result = service.updateRegistrationRiskParam(
                "maxSignupPerIp24h",
                "idem-c6-k1",
                new UserRegistrationRiskParamUpdateRequest("3", "wrong page should be rejected", "superadmin", 0L));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("MULTI_ACCOUNT_PARAM_BELONGS_TO_K1");
    }

    @Test
    void captchaOffWindowIsPersistedAndRejectsRawUrl() {
        configFacade.values.put("auth.risk.c6.version", "0");
        ApiResult<UserRegistrationRiskParamView> disabled = service.updateRegistrationRiskParam(
                "captchaOff",
                "idem-c6-captcha",
                new UserRegistrationRiskParamUpdateRequest("2 小时后自动恢复", "captcha provider outage window", "superadmin", 0L));
        ApiResult<UserRegistrationRiskParamView> rawUrl = service.updateRegistrationRiskParam(
                "captchaOff",
                "idem-c6-captcha-url",
                new UserRegistrationRiskParamUpdateRequest("https://status.example.com", "captcha provider outage window", "superadmin", 1L));

        assertThat(disabled.getCode()).isZero();
        assertThat(configFacade.values.get("auth.risk.captcha_off_window")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(rawUrl.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void disablingTwoFactorRequiresIdempotencyAndUpdatesSecurity() {
        userRepository.twoFactorEnabled = true;
        userRepository.updateKycStatus(1L, "APPROVED", "test setup");

        ApiResult<UserSecurityStatusView> result = service.disableTwoFactor(
                1L,
                "idem-c5-2fa",
                verifiedC5Request("verified support ownership", null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().twoFactorEnabled()).isFalse();
        assertThat(userRepository.twoFactorEnabled).isFalse();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C5_TWO_FACTOR_DISABLED");
        verify(outboxService).publish(eq("USER_SECURITY"), eq("1"), eq("admin.2fa_disabled"), any());
    }

    @Test
    void passwordResetMarksResetRequiredAndRevokesActiveSessions() {
        userRepository.updateKycStatus(1L, "APPROVED", "test setup");
        userRepository.sessions.put("rt-active", new UserSessionView(
                1L, "rt-active", "web", "10.0.0.*", "ACTIVE", LocalDateTime.now(), LocalDateTime.now().plusDays(1), null));

        ApiResult<UserSecurityStatusView> result = service.requestPasswordReset(
                1L,
                "idem-c5-password",
                verifiedC5Request("verified support ownership", null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().passwordResetRequired()).isTrue();
        assertThat(userRepository.passwordResetMarker).startsWith("RESET_REQUIRED$");
        assertThat(userRepository.sessions.get("rt-active").status()).isEqualTo("REVOKED");
    }

    @Test
    void unlockClearsLoginFailures() {
        userRepository.updateKycStatus(1L, "APPROVED", "test setup");
        userRepository.loginFailCount = 8;
        userRepository.activeLoginLock = true;

        ApiResult<UserSecurityStatusView> result = service.unlockSecurity(
                1L,
                "idem-c5-unlock",
                verifiedC5Request("risk review passed", "SHORT"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().loginFailCount()).isZero();
        assertThat(result.getData().locked()).isFalse();
    }

    @Test
    void securityStatusUsesCanonicalActiveLoginGuardInsteadOfHistoricalFailureCount() {
        userRepository.loginFailCount = 8;
        userRepository.activeLoginLock = false;

        ApiResult<UserSecurityStatusView> expiredLock = service.securityStatus(1L);

        assertThat(expiredLock.getCode()).isZero();
        assertThat(expiredLock.getData().locked()).isFalse();

        userRepository.loginFailCount = 1;
        userRepository.activeLoginLock = true;

        ApiResult<UserSecurityStatusView> activeLock = service.securityStatus(1L);

        assertThat(activeLock.getCode()).isZero();
        assertThat(activeLock.getData().locked()).isTrue();
    }

    @Test
    void c5HighRiskActionRejectsMissingServerVerifiedKycEvidence() {
        userRepository.twoFactorEnabled = true;

        ApiResult<UserSecurityStatusView> result = service.disableTwoFactor(
                1L, "idem-c5-missing-kyc", new UserSecurityActionRequest("verified support ownership", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("KYC_REVERIFY_REQUIRED");
        assertThat(userRepository.twoFactorEnabled).isTrue();
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
    }

    @Test
    void c5HighRiskActionRejectsSyntacticallyValidButUnissuedKycTicket() {
        userRepository.twoFactorEnabled = true;
        userRepository.updateKycStatus(1L, "APPROVED", "test setup");
        UserSecurityActionRequest forged = new UserSecurityActionRequest(
                "verified support ownership",
                "superadmin",
                "video",
                "KR-C5-FORGED-0001",
                LocalDateTime.now().toString(),
                true,
                null);

        ApiResult<UserSecurityStatusView> result = service.disableTwoFactor(
                1L, "idem-c5-forged-ticket", forged);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("KYC_REVERIFY_REQUIRED");
        assertThat(userRepository.twoFactorEnabled).isTrue();
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
    }

    @Test
    void c5KycReverificationRequestCreatesActionBoundK5Ticket() {
        userRepository.updateKycStatus(1L, "APPROVED", "test setup");
        when(riskKycReviewFacade.triggerC5IdentityReview(
                eq("U00000001"), eq("PASSWORD_RESET"), eq("superadmin"), eq("customer identity recheck")))
                .thenReturn(new KycReviewTriggerResult(true, true, "KR-C5-ABC12345", "K5_C5_REVIEW_CREATED"));

        ApiResult<Map<String, Object>> result = service.requestC5KycReverification(
                1L,
                "idem-c5-review-request",
                new ffdd.opsconsole.user.dto.UserKycReverificationRequest(
                        "PASSWORD_RESET", "customer identity recheck", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("ticketId", "KR-C5-ABC12345")
                .containsEntry("action", "PASSWORD_RESET")
                .containsEntry("status", "WAITING_K5_REVIEW");
        verify(riskKycReviewFacade).triggerC5IdentityReview(
                "U00000001", "PASSWORD_RESET", "superadmin", "customer identity recheck");
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void c5RepeatedStateTransitionIsRejectedInsteadOfPretendingSuccess() {
        userRepository.twoFactorEnabled = false;
        userRepository.updateKycStatus(1L, "APPROVED", "test setup");

        ApiResult<UserSecurityStatusView> result = service.disableTwoFactor(
                1L, "idem-c5-state", verifiedC5Request("verified support ownership", null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("C5_ACTION_STATE_CHANGED");
    }

    @Test
    void c5CredentialParamsUseDocumentedFallbacksWhenConfigRowsAreMissing() {
        List<UserCredentialParamView> params = service.credentialParams().getData();

        assertThat(params).extracting(UserCredentialParamView::value)
                .contains("4 小时", "30 天", "30 天", "7 天");
    }

    private UserSecurityActionRequest verifiedC5Request(String reason, String lockKind) {
        return new UserSecurityActionRequest(
                reason,
                "superadmin",
                "视频核实",
                "SEC-20260719-001",
                LocalDateTime.now().toString(),
                true,
                lockKind);
    }

    @Test
    void securityOverviewReadsExistingC5BusinessRows() {
        userRepository.sessions.clear();
        userRepository.loadSecuritySessionFixtures();

        ApiResult<UserSecurityOverview> result = service.securityOverview("usr_2231", null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().selectedUser().userNo()).isEqualTo("U00002231");
        assertThat(result.getData().sessions().getRecords()).extracting(UserSessionView::refreshTokenId)
                .contains("C5-SESSION-usr_2231-IOS");
        assertThat(result.getData().lockedUsers()).extracting(UserSecurityUserRow::userNo)
                .contains("U00008807", "U00003315");
        assertThat(result.getData().sources()).contains("nx_user_security", "nx_user_session");
    }

    @Test
    void securityOverviewWithoutAnExplicitTargetNeverSelectsTheFirstUser() {
        ApiResult<UserSecurityOverview> result = service.securityOverview(null, null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().selectedUser()).isNull();
        assertThat(result.getData().sessions().getRecords()).isEmpty();
        assertThat(result.getData().selectedActiveSessionCount()).isZero();
    }

    @Test
    void securityOverviewReadsLockedUsersFromUserSecurityTable() {
        userRepository.loadSecuritySessionFixtures();
        userRepository.omitC5UsersFromGenericSearch = true;

        ApiResult<UserSecurityOverview> result = service.securityOverview("U00002231", null, 1, 10, null);

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.lockedSecurityUsersCalls).isGreaterThan(0);
        assertThat(result.getData().lockedUsers()).extracting(UserSecurityUserRow::userNo)
                .containsExactly("U00003315", "U00008807");
    }

    @Test
    void replayC2AccountFreezeDerivesStatusFromOperationInsteadOfClientParams() {
        AuditReplayCommand cmd = new AuditReplayCommand("C", "c2_account_freeze", Map.of(
                "userId", 1L,
                "status", "ACTIVE",
                "reasonCode", "RISK_HIT"));
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay freeze user", "idem-replay-c2-freeze");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.user.status()).isEqualTo("FROZEN");
    }

    @Test
    void financeProposerCannotBypassA2WithDirectC2ServiceCallButApprovedReplayStillWorks() {
        when(roleResolver.resolveCode()).thenReturn("FINANCE");
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "finance-user", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_proposal_create"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("user_c2_account_freeze")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var denied = service.updateStatus(
                    1L, "idem-direct-bypass",
                    new UserStatusUpdateRequest("FROZEN", "RISK_HIT", "attempt direct delegated freeze", "finance-user"));

            assertThat(denied.getCode()).isEqualTo(403);
            assertThat(denied.getMessage()).isEqualTo("A2_PROPOSAL_REQUIRED");
            assertThat(userRepository.user.status()).isEqualTo("ACTIVE");
            verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                    "A2_DIRECT_EXECUTION_REJECTED".equals(audit.getAction())
                            && "REJECTED".equals(audit.getResult())
                            && audit.getDetail() instanceof Map<?, ?> detail
                            && Boolean.FALSE.equals(detail.get("businessDataChanged"))));

            ffdd.opsconsole.platform.application.A2ReplayContext.enterReplay();
            try {
                var replayed = service.replay(
                        new AuditReplayCommand("C", "c2_account_freeze", Map.of(
                                "userId", 1L, "status", "ACTIVE", "reasonCode", "RISK_HIT")),
                        new AuditReplayContext("approver", "approved replay freeze", "idem-approved-replay"));
                assertThat(replayed.getCode()).isZero();
                assertThat(userRepository.user.status()).isEqualTo("FROZEN");
            } finally {
                ffdd.opsconsole.platform.application.A2ReplayContext.exitReplay();
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void financeRoleRevokesSessionsDirectlyBecauseForcedLogoutIsAnImmediateContainmentAction() {
        when(roleResolver.resolveCode()).thenReturn("FINANCE");
        userRepository.sessions.put("rt-finance", new UserSessionView(
                1L, "rt-finance", "web", "10.0.0.*", "ACTIVE",
                LocalDateTime.now(), LocalDateTime.now().plusHours(1), null));
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "finance-user", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_proposal_create"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("user_c2_session_revoke_all")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<Map<String, Object>> result = service.revokeUserSessions(
                    1L,
                    "idem-finance-containment",
                    new UserSessionRevokeAllRequest("urgent finance containment", "forged-operator"));

            assertThat(result.getCode()).isZero();
            assertThat(result.getData()).containsEntry("status", "REVOKED").containsEntry("revokedCount", 1L);
            ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
            verify(auditLogService).recordRequired(audit.capture());
            assertThat(audit.getValue().getAction()).isEqualTo("C2_USER_SESSIONS_REVOKED");
            assertThat(audit.getValue().getActorUsername()).isEqualTo("admin:finance-user");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void riskRoleExecutesC2DirectlyEvenWhenItCanCreateA2ProposalsElsewhere() {
        when(roleResolver.resolveCode()).thenReturn("RISK");
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-user", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_proposal_create"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("user_c2_account_freeze")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<UserAccountView> result = service.updateStatus(
                    1L,
                    "idem-risk-direct-c2",
                    new UserStatusUpdateRequest("FROZEN", "RISK_HIT", "risk lead direct C2 freeze", "risk-user"));

            assertThat(result.getCode()).isZero();
            assertThat(userRepository.user.status()).isEqualTo("FROZEN");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void replayC3AdjustApproveInvokesApproveAndSucceeds() {
        String adjustmentNo = userRepository.seedPendingAdjustment("NEX", "DEBIT", "2.5");
        AuditReplayCommand cmd = new AuditReplayCommand("C", "c3_adjust_approve", Map.of(
                "adjustmentNo", adjustmentNo));
        AuditReplayContext ctx = new AuditReplayContext("checker", "replay approve adjustment", "idem-replay-c3-approve");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isZero();
        assertThat(userRepository.adjustments.get(adjustmentNo).status()).isEqualTo("APPROVED");
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        AuditReplayCommand cmd = new AuditReplayCommand("C", "c2_unknown_op", Map.of());
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay unknown op", "idem-replay-unknown");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:c2_unknown_op");
    }

    private UserAssetAdjustmentRequest c3Request(
            String asset,
            String direction,
            String amount,
            String reasonCode,
            String reason,
            String evidenceRef) {
        return new UserAssetAdjustmentRequest(
                asset,
                direction,
                amount,
                reasonCode,
                reason,
                evidenceRef,
                null,
                "superadmin",
                null,
                null);
    }

    private static final class FakeFinanceWithdrawalControlFacade implements FinanceWithdrawalControlFacade {
        private Long lastUserId;
        private String lastReason;
        private String lastOperator;
        private int updatedCount;

        @Override
        public int freezePendingWithdrawalsForUser(Long userId, String reason, String operator) {
            lastUserId = userId;
            lastReason = reason;
            lastOperator = operator;
            return updatedCount;
        }

        @Override
        public int restoreWithdrawalsFrozenByUserStatus(Long userId, String reason, String operator) {
            lastUserId = userId;
            lastReason = reason;
            lastOperator = operator;
            return updatedCount;
        }
    }

    private static final class FakeRiskUserStateFacade implements RiskUserStateFacade {
        private Long lastUserId;
        private String lastUserNo;
        private String lastReason;
        private String lastOperator;

        @Override
        public void recordUserFrozen(Long userId, String userNo, String reason, String operator) {
            lastUserId = userId;
            lastUserNo = userNo;
            lastReason = reason;
            lastOperator = operator;
        }
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
        private final List<Map<String, Object>> postedLedgerBills = new ArrayList<>();
        private RuntimeException approvalFailure;
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
        private boolean rejectNextStatusTransition = false;
        private boolean rejectNextImpersonationTransition = false;
        private String freezeSource;
        private String freezeSourceRef;
        private int lockedSecurityUsersCalls = 0;
        private boolean twoFactorEnabled = false;
        private int loginFailCount = 0;
        private boolean activeLoginLock = false;
        private long registrationOtpToday;
        private long registrationCaptchaToday;
        private long registrationShortLocksToday;
        private long registrationLongLocksToday;
        private long registrationStuffingClusters7d;
        private String passwordResetMarker;
        private UserQueryRequest lastProfileRequest;
        private UserAssetAdjustmentQueryRequest lastAssetAdjustmentRequest;
        private Long lastSessionPageUserId;
        private long nextLedgerId = 9001L;

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
        public long countRegistrationOtpToday() {
            return registrationOtpToday;
        }

        @Override
        public long countRegistrationCaptchaTriggeredToday() {
            return registrationCaptchaToday;
        }

        @Override
        public long countRegistrationLoginLocksToday(String lockType) {
            return "LONG".equals(lockType) ? registrationLongLocksToday : registrationShortLocksToday;
        }

        @Override
        public long countRegistrationStuffingClusters7d() {
            return registrationStuffingClusters7d;
        }

        @Override
        public List<UserAccountView> search(String keyword, String status, String kycStatus, int limit) {
            return filterUsers(status, kycStatus).stream().limit(limit).toList();
        }

        @Override
        public List<UserAccountControlFactView> accountControlFacts(int limit) {
            if (!"FROZEN".equalsIgnoreCase(user.status())) return List.of();
            return List.of(new UserAccountControlFactView(
                    user.id(), freezeSource, freezeSourceRef, "test freeze", "superadmin", LocalDateTime.now(), 1));
        }

        @Override
        public Optional<UserAccountControlFactView> findAccountControlFact(Long userId) {
            return accountControlFacts(100).stream()
                    .filter(fact -> userId != null && userId.equals(fact.userId()))
                    .findFirst();
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
        public PageResult<UserKycRecord> pageKycRecords(String kycStatus, int pageNum, int pageSize) {
            List<UserKycRecord> all = allUsers().stream()
                    .filter(account -> kycStatus == null || kycStatus.equals(account.kycStatus()))
                    .map(this::toKycRecord)
                    .toList();
            int from = Math.min(Math.max(0, (pageNum - 1) * pageSize), all.size());
            int to = Math.min(from + pageSize, all.size());
            return new PageResult<>(all.size(), pageNum, pageSize, all.subList(from, to));
        }

        @Override
        public Optional<UserKycRecord> findKycRecord(Long userId) {
            return findById(userId).map(this::toKycRecord);
        }

        @Override
        public List<UserKycStatusHistoryView> kycStatusHistory(Long userId, int limit) {
            return List.of(new UserKycStatusHistoryView(
                    null, findById(userId).map(UserAccountView::kycStatus).orElse("NONE"),
                    "LEGACY_MIGRATION", "fixture seed", "fixture", "LEGACY_MIGRATION",
                    "test", null, LocalDateTime.now()));
        }

        @Override
        public boolean transitionKycStatus(
                Long userId, String expectedStatus, long expectedVersion, String nextStatus,
                String reasonCode, String reason, String evidenceRef, String source,
                String operator, String idempotencyKey, String ticketId) {
            UserAccountView current = findById(userId).orElse(null);
            if (current == null || !expectedStatus.equals(current.kycStatus())) return false;
            updateKycStatus(userId, nextStatus, reason);
            return true;
        }

        private UserKycRecord toKycRecord(UserAccountView account) {
            return new UserKycRecord(
                    account.id(), account.userNo(), account.nickname(), account.phoneMasked(), account.countryCode(),
                    account.status(), account.userLevel(), account.kycStatus(), walletAddresses.get(account.id()),
                    null, null, "LEGACY_MIGRATION", 0L);
        }

        @Override
        public Optional<UserAccountView> findById(Long userId) {
            return allUsers().stream()
                    .filter(account -> userId != null && userId.equals(account.id()))
                    .findFirst();
        }

        @Override
        public List<UserReadonlyDeviceView> readonlyDevices(Long userId, int limit) {
            if (!user.id().equals(userId)) return List.of();
            return List.of(new UserReadonlyDeviceView(
                    "DEV-0001", "Alice GPU", "GPU", 2, "ONLINE",
                    new BigDecimal("80"), new BigDecimal("1.20"), new BigDecimal("3.40"), LocalDateTime.now()));
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

        private void loadAccountActionFixtures() {
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

        private void loadKycFixtures() {
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

        private void loadAssetAdjustmentFixtures() {
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

        private String seedPendingAdjustment(String asset, String direction, String amountText) {
            String adjustmentNo = "ADJ-TEST-" + (adjustments.size() + 1);
            BigDecimal amount = new BigDecimal(amountText);
            createAssetAdjustment(
                    adjustmentNo, 1L, asset, direction, amount, amount,
                    "OPS_USER_ADJUSTMENT", "pending compatibility record for review test",
                    "ticket:test-review", "idem:" + adjustmentNo, null, "superadmin");
            return adjustmentNo;
        }

        private void loadSecuritySessionFixtures() {
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
                    activeLoginLock,
                    passwordResetMarker != null,
                    0,
                    0));
        }

        @Override
        public boolean canUseC5KycReverification(
                Long userId, String ticketId, String action, int rememberDays, String idempotencyKey) {
            return "SEC-20260719-001".equals(ticketId) && action != null && idempotencyKey != null;
        }

        @Override
        public boolean consumeC5KycReverification(
                Long userId, String ticketId, String action, String idempotencyKey, String operator) {
            return canUseC5KycReverification(userId, ticketId, action, 7, idempotencyKey);
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
        public long countActiveSessions(Long userId) {
            return sessions.values().stream()
                    .filter(session -> userId == null || userId.equals(session.userId()))
                    .filter(session -> "ACTIVE".equalsIgnoreCase(session.status()))
                    .count();
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
        public List<UserImpersonationSessionView> impersonations(Long userId, int limit) {
            return impersonations.stream()
                    .filter(session -> userId != null && userId.equals(session.userId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countImpersonations(Long userId) {
            return impersonations.stream()
                    .filter(session -> userId != null && userId.equals(session.userId()))
                    .count();
        }

        @Override
        public Optional<UserImpersonationSessionView> findImpersonation(String sessionNo) {
            return impersonations.stream()
                    .filter(session -> session.sessionNo().equals(sessionNo))
                    .findFirst();
        }

        @Override
        public void lockUser(Long userId) {
        }

        @Override
        public boolean hasActiveImpersonation(Long userId) {
            return impersonations.stream().anyMatch(session -> userId.equals(session.userId())
                    && "ACTIVE".equalsIgnoreCase(session.status())
                    && session.expiresAt().isAfter(LocalDateTime.now()));
        }

        @Override
        public List<UserImpersonationSessionView> expiredActiveImpersonations(int limit) {
            return impersonations.stream()
                    .filter(session -> "ACTIVE".equalsIgnoreCase(session.status()))
                    .filter(session -> !session.expiresAt().isAfter(LocalDateTime.now()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean expireActiveImpersonation(String sessionNo, String reason, String operator) {
            UserImpersonationSessionView current = findImpersonation(sessionNo).orElse(null);
            if (current == null || !"ACTIVE".equalsIgnoreCase(current.status())
                    || current.expiresAt().isAfter(LocalDateTime.now())) return false;
            impersonations.remove(current);
            impersonations.add(new UserImpersonationSessionView(
                    current.sessionNo(), current.userId(), current.userNo(), current.nickname(), "EXPIRED",
                    current.ttlMinutes(), current.operator(), current.reason(), current.expiresAt(), current.createdAt(),
                    LocalDateTime.now(), operator, reason, 0L));
            return true;
        }

        @Override
        public boolean terminateActiveImpersonation(String sessionNo, String reason, String operator) {
            if (rejectNextImpersonationTransition) {
                rejectNextImpersonationTransition = false;
                return false;
            }
            UserImpersonationSessionView current = findImpersonation(sessionNo).orElseThrow();
            impersonations.removeIf(session -> session.sessionNo().equals(sessionNo));
            impersonations.add(new UserImpersonationSessionView(
                    current.sessionNo(), current.userId(), current.userNo(), current.nickname(), "TERMINATED",
                    current.ttlMinutes(), current.operator(), current.reason(), current.expiresAt(), current.createdAt(),
                    LocalDateTime.now(), operator, reason, 0L));
            return true;
        }

        @Override
        public boolean transitionUserStatus(Long userId, String expectedStatus, String status, String reason) {
            if (rejectNextStatusTransition) {
                rejectNextStatusTransition = false;
                return false;
            }
            if (!expectedStatus.equalsIgnoreCase(user.status())) return false;
            user = new UserAccountView(
                    user.id(), user.userNo(), user.nickname(), user.phoneMasked(), user.countryCode(), status, user.kycStatus(),
                    user.userLevel(), user.vRank(), user.twoFactorEnabled(), user.walletUsdt(), user.walletNex(),
                    user.riskScore(), user.riskBand(), user.deviceCount(), user.activeDeviceCount(),
                    user.registeredAt(), user.lastLoginAt());
            if ("ACTIVE".equalsIgnoreCase(status)) {
                freezeSource = null;
                freezeSourceRef = null;
            }
            return true;
        }

        @Override
        public boolean freezeUserStatusWithSource(
                Long userId, String expectedStatus, String reason, String operator, String source, String sourceRef) {
            boolean changed = transitionUserStatus(userId, expectedStatus, "FROZEN", reason);
            if (changed) {
                freezeSource = source;
                freezeSourceRef = sourceRef;
            }
            return changed;
        }

        @Override
        public boolean isFrozenBySource(Long userId, String source) {
            return userId.equals(user.id()) && source != null && source.equals(freezeSource);
        }

        @Override
        public boolean restoreUserStatusByFreezeSource(Long userId, String source, String sourceRef) {
            if (!isFrozenBySource(userId, source) || !java.util.Objects.equals(freezeSourceRef, sourceRef)) return false;
            return transitionUserStatus(userId, "FROZEN", "ACTIVE", "source release");
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
        public boolean revokeSession(String refreshTokenId, String reason) {
            UserSessionView session = sessions.get(refreshTokenId);
            if (session == null || "REVOKED".equalsIgnoreCase(session.status())) return false;
            sessions.put(refreshTokenId, new UserSessionView(
                    session.userId(), session.refreshTokenId(), session.deviceName(), session.clientIpMasked(), "REVOKED",
                    session.issuedAt(), session.expiresAt(), LocalDateTime.now()));
            return true;
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
        public boolean disableTwoFactor(Long userId) {
            UserSecurityStatusView c5Status = c5SecurityRows.get(userId);
            if (c5Status != null) {
                if (!c5Status.twoFactorEnabled()) return false;
                c5SecurityRows.put(userId, new UserSecurityStatusView(
                        userId, false, c5Status.loginFailCount(), false, c5Status.passwordResetRequired(), 0, 0));
                return true;
            }
            if (!twoFactorEnabled) return false;
            twoFactorEnabled = false;
            return true;
        }

        @Override
        public boolean markPasswordResetRequired(Long userId, String resetMarker) {
            UserSecurityStatusView c5Status = c5SecurityRows.get(userId);
            if (c5Status != null) {
                if (c5Status.passwordResetRequired()) return false;
                c5SecurityRows.put(userId, new UserSecurityStatusView(
                        userId, c5Status.twoFactorEnabled(), c5Status.loginFailCount(), false, true, 0, 0));
                return true;
            }
            if (passwordResetMarker != null) return false;
            passwordResetMarker = resetMarker;
            return true;
        }

        @Override
        public boolean resetLoginFailures(Long userId) {
            UserSecurityStatusView c5Status = c5SecurityRows.get(userId);
            if (c5Status != null) {
                if (c5Status.loginFailCount() <= 0) return false;
                c5SecurityRows.put(userId, new UserSecurityStatusView(
                        userId, c5Status.twoFactorEnabled(), 0, false, c5Status.passwordResetRequired(), 0, 0));
                return true;
            }
            if (loginFailCount <= 0) return false;
            loginFailCount = 0;
            activeLoginLock = false;
            return true;
        }

        @Override
        public void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt) {
            impersonations.add(new UserImpersonationSessionView(
                    sessionNo, userId, "U00000001", "Alice", "ACTIVE", ttlMinutes, operator, reason, expiresAt,
                    LocalDateTime.now(), null, null, null, (long) ttlMinutes));
        }

        @Override
        public void createAssetAdjustment(
                String adjustmentNo, Long userId, String asset, String direction, BigDecimal amount,
                BigDecimal amountUsd, String reasonCode, String reason, String evidenceRef,
                String idempotencyKey, String reversalOf, String operator) {
            adjustments.put(adjustmentNo, adjustment(
                    adjustmentNo, userId, asset, direction, amount, amountUsd, reasonCode, reason,
                    evidenceRef, idempotencyKey, reversalOf, operator, "PENDING_REVIEW", null, null, null));
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
        public Long approveAssetAdjustmentAndPostLedger(UserAssetAdjustmentView adjustment, String checker, String reason) {
            if (approvalFailure != null) {
                throw approvalFailure;
            }
            long ledgerId = nextLedgerId++;
            postedLedgerBills.add(new LinkedHashMap<>(Map.of(
                    "ledgerId", ledgerId,
                    "bizNo", adjustment.adjustmentNo(),
                    "bizType", "ADJUSTMENT",
                    "userId", adjustment.userId(),
                    "asset", adjustment.asset(),
                    "direction", "CREDIT".equals(adjustment.direction()) ? "IN" : "OUT",
                    "amount", adjustment.amount(),
                    "status", "SUCCESS")));
            adjustments.put(adjustment.adjustmentNo(), adjustment(
                    adjustment.adjustmentNo(),
                    adjustment.userId(),
                    adjustment.asset(),
                    adjustment.direction(),
                    adjustment.amount(),
                    adjustment.amountUsd(),
                    adjustment.reasonCode(),
                    adjustment.reason(),
                    adjustment.evidenceRef(),
                    adjustment.idempotencyKey(),
                    adjustment.reversalOf(),
                    adjustment.maker(),
                    "APPROVED",
                    checker,
                    reason,
                    ledgerId));
            return ledgerId;
        }

        @Override
        public boolean reviewAssetAdjustment(String adjustmentNo, String status, String checker, String reason) {
            UserAssetAdjustmentView before = adjustments.get(adjustmentNo);
            if (before == null || !List.of("PENDING", "PENDING_REVIEW", "SUSPENDED").contains(before.status())) {
                return false;
            }
            adjustments.put(adjustmentNo, adjustment(
                    before.adjustmentNo(),
                    before.userId(),
                    before.asset(),
                    before.direction(),
                    before.amount(),
                    before.amountUsd(),
                    before.reasonCode(),
                    before.reason(),
                    before.evidenceRef(),
                    before.idempotencyKey(),
                    before.reversalOf(),
                    before.maker(),
                    status,
                    checker,
                    reason,
                    before.ledgerId()));
            return true;
        }

        @Override
        public boolean assetAdjustmentHasReversal(String adjustmentNo) {
            return adjustments.values().stream().anyMatch(row -> adjustmentNo.equals(row.reversalOf()));
        }

        @Override
        public BigDecimal findWalletPendingWithdraw(Long userId) {
            return BigDecimal.ZERO;
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
            return adjustment(adjustmentNo, userId, asset, direction, amount, reason, maker, status, checker, reviewReason, null);
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
                String reviewReason,
                Long ledgerId) {
            return adjustment(adjustmentNo, userId, asset, direction, amount, "OPS_USER_ADJUSTMENT", reason, maker, status, checker, reviewReason, ledgerId);
        }

        private UserAssetAdjustmentView adjustment(
                String adjustmentNo,
                Long userId,
                String asset,
                String direction,
                BigDecimal amount,
                String reasonCode,
                String reason,
                String maker,
                String status,
                String checker,
                String reviewReason,
                Long ledgerId) {
            return adjustment(adjustmentNo, userId, asset, direction, amount, amount, reasonCode, reason,
                    "legacy:" + adjustmentNo, "legacy:" + adjustmentNo, null, maker, status, checker, reviewReason, ledgerId);
        }

        private UserAssetAdjustmentView adjustment(
                String adjustmentNo,
                Long userId,
                String asset,
                String direction,
                BigDecimal amount,
                BigDecimal amountUsd,
                String reasonCode,
                String reason,
                String evidenceRef,
                String idempotencyKey,
                String reversalOf,
                String maker,
                String status,
                String checker,
                String reviewReason,
                Long ledgerId) {
            BigDecimal currentBalance = "USDT".equals(asset) ? user.walletUsdt() : user.walletNex();
            BigDecimal balanceAfter = "CREDIT".equals(direction)
                    ? currentBalance.add(amount)
                    : currentBalance.subtract(amount);
            return new UserAssetAdjustmentView(
                    adjustmentNo,
                    userId,
                    user.userNo(),
                    user.nickname(),
                    asset,
                    direction,
                    amount,
                    amountUsd,
                    ("CREDIT".equals(direction) ? "+" : "-") + amount + ("USDT".equals(asset) ? " USDT" : " NEX"),
                    reasonCode,
                    reason,
                    evidenceRef,
                    idempotencyKey,
                    reversalOf,
                    null,
                    maker,
                    checker,
                    status,
                    status,
                    "APPROVED".equals(status) ? "ok" : "REJECTED".equals(status) || "SUSPENDED".equals(status) ? "bad" : "warn",
                    "CREDIT".equals(direction),
                    false,
                    ledgerId,
                    ledgerId == null ? null : balanceAfter,
                    ledgerId == null ? "D4 待过账" : "账本 #" + ledgerId,
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
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(
                new BigDecimal("110.00"), new BigDecimal("85.00"), true,
                new BigDecimal("110000"), new BigDecimal("100000"), BigDecimal.ONE);

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
