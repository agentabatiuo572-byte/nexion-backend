package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalKycReviewFacade;
import ffdd.opsconsole.market.facade.MarketExchangeKycReviewFacade;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.domain.KycReviewTicketContext;
import ffdd.opsconsole.risk.domain.RiskArbitrageParamView;
import ffdd.opsconsole.risk.domain.RiskArbitrageRowView;
import ffdd.opsconsole.risk.domain.RiskArbitrageStatView;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskRouteCountView;
import ffdd.opsconsole.risk.domain.RiskRuleDimensionView;
import ffdd.opsconsole.risk.domain.RiskRuleHitView;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.domain.RiskScoreConfigView;
import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreDimensionView;
import ffdd.opsconsole.risk.domain.RiskScoreDistributionView;
import ffdd.opsconsole.risk.domain.RiskScoreOverrideView;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.risk.domain.RiskScoreUserSearchView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.domain.RiskWithdrawCandidateView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskArbitrageParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskClusterStatusRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskKycManualReviewRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskKycAlertSubscriptionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleCreateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleDryRunRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelDraftRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelPublishRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelRestoreRequest;
import ffdd.opsconsole.risk.dto.RiskScoringSourceRequest;
import ffdd.opsconsole.risk.dto.RiskScoringWeightsRequest;
import ffdd.opsconsole.risk.dto.RiskRuleConditionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import ffdd.opsconsole.risk.dto.RiskScoringOverviewQueryRequest;
import ffdd.opsconsole.user.facade.UserKycStatusFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import ffdd.opsconsole.shared.api.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;

class OpsRiskServiceTest {
    private final FakeRiskOpsRepository riskRepository = new FakeRiskOpsRepository();
    private final FakeUserKycStatusFacade userKycStatusFacade = new FakeUserKycStatusFacade();
    private final FakeFinanceWithdrawalKycReviewFacade financeWithdrawalKycReviewFacade = new FakeFinanceWithdrawalKycReviewFacade();
    private final FakeMarketExchangeKycReviewFacade marketExchangeKycReviewFacade = new FakeMarketExchangeKycReviewFacade();
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final ffdd.opsconsole.shared.security.SuperAdminAuthorization superAdminAuthorization =
            mock(ffdd.opsconsole.shared.security.SuperAdminAuthorization.class);
    private final ffdd.opsconsole.user.facade.UserAccountControlFacade userAccountControlFacade =
            mock(ffdd.opsconsole.user.facade.UserAccountControlFacade.class);
    private final EventOutboxService eventOutboxService = mock(EventOutboxService.class);
    private final ChainAddressReputationGateway chainAddressReputationGateway = mock(ChainAddressReputationGateway.class);
    private final K4KycReviewTriggerService k4KycReviewTriggerService =
            new K4KycReviewTriggerService(riskRepository, auditLogService);
    private final OpsRiskService service = new OpsRiskService(
            riskRepository,
            userKycStatusFacade,
            financeWithdrawalKycReviewFacade,
            marketExchangeKycReviewFacade,
            configFacade,
            auditLogService,
            lockMapper,
            idempotencyService,
            superAdminAuthorization,
            userAccountControlFacade,
            eventOutboxService,
            chainAddressReputationGateway,
            k4KycReviewTriggerService);

    @BeforeEach
    void stubLockMapperNoActiveLock() {
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
        when(configFacade.activeValue(anyString())).thenReturn(Optional.empty());
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .execute(anyString(), anyString(), anyString(), any(), any());
    }

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void k1MutationsKeepIdempotencyBusinessStateAndRequiredAuditInOneTransaction() {
        List<String> mutations = List.of(
                "updateMultiAccountParam",
                "updateMultiAccountClusterStatus",
                "upsertIpWhitelist",
                "updateMultiAccountClusterReviewNote",
                "disableIpWhitelist");

        assertThat(java.util.Arrays.stream(OpsRiskService.class.getDeclaredMethods())
                .filter(method -> mutations.contains(method.getName()))
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList())
                .containsExactlyInAnyOrderElementsOf(mutations);
    }

    @Test
    void k2MutationsKeepIdempotencyBusinessStateAndRequiredAuditInOneTransaction() {
        List<String> mutations = List.of("updateArbitrageParam", "executeArbitrageAction");

        assertThat(java.util.Arrays.stream(OpsRiskService.class.getDeclaredMethods())
                .filter(method -> mutations.contains(method.getName()))
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList())
                .containsExactlyInAnyOrderElementsOf(mutations);
    }

    @Test
    void k3MutationsKeepIdempotencyBusinessStateAndRequiredAuditInOneTransaction() {
        List<String> mutations = List.of(
                "createWithdrawRule", "updateWithdrawRuleState",
                "updateWithdrawRuleCondition", "dryRunWithdrawRules");

        assertThat(java.util.Arrays.stream(OpsRiskService.class.getDeclaredMethods())
                .filter(method -> mutations.contains(method.getName()))
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList())
                .containsExactlyInAnyOrderElementsOf(mutations);
    }

    @Test
    void k5MutationsKeepIdempotencyBusinessStateAndRequiredAuditInOneTransaction() {
        List<String> mutations = List.of(
                "updateKycReviewParam", "decideKycReviewTicket",
                "createManualKycReviewTicket", "updateKycAlertSubscription");

        assertThat(java.util.Arrays.stream(OpsRiskService.class.getDeclaredMethods())
                .filter(method -> mutations.contains(method.getName()))
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .map(java.lang.reflect.Method::getName)
                .distinct().toList())
                .containsExactlyInAnyOrderElementsOf(mutations);
    }

    @Test
    void overviewDeclaresDecisionStates() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("decisionStates")).asList().contains("REVIEWING", "FINALIZED");
    }

    @Test
    void casesReturnsServerCanonicalPagination() {
        ApiResult<PageResult<RiskCaseView>> result = service.cases(
                new RiskCaseQueryRequest(1L, "OPEN", "REVIEW", 2, 25, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        assertThat(result.getData().getPageSize()).isEqualTo(25);
        assertThat(result.getData().getTotal()).isEqualTo(1);
        assertThat(riskRepository.lastPageRequest.userId()).isEqualTo(1L);
        assertThat(riskRepository.lastPageRequest.status()).isEqualTo("OPEN");
        assertThat(riskRepository.lastPageRequest.decision()).isEqualTo("REVIEW");
    }

    @Test
    void decidingFinalizedCaseReturns409() {
        riskRepository.caseView = new RiskCaseView(
                "RD-1", 1L, "WITHDRAWAL", "W-1", "US", "L1", "ALLOW", "ok", 20, "[]", "FINALIZED", "admin",
                LocalDateTime.now(), LocalDateTime.now().minusDays(1));

        ApiResult<RiskCaseView> result = service.decide(
                "RD-1",
                "idem-k",
                new RiskDecisionRequest("BLOCK", "late review", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void manualDecisionUpdatesCaseAndAudits() {
        ApiResult<RiskCaseView> result = service.decide(
                "RD-1",
                "idem-k",
                new RiskDecisionRequest("BLOCK", "fraud evidence", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().decision()).isEqualTo("BLOCK");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K_RISK_CASE_DECIDED");
    }

    @Test
    void signalRequiresIdempotencyKey() {
        ApiResult<Map<String, Object>> result = service.recordSignal(
                null,
                new RiskSignalRequest(1L, "device_fingerprint", "HIGH", "{}", "new signal", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void manualKycSignalCreatesBackendReviewCase() {
        ApiResult<Map<String, Object>> result = service.recordSignal(
                "idem-kyc",
                new RiskSignalRequest(9921L, "KYC_REVIEW_MANUAL", "HIGH", "source=ops-console", "manual kyc review", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("status")).isEqualTo("REVIEW_CREATED");
        assertThat(result.getData().get("caseNo")).asString().startsWith("KYC-");
        assertThat(riskRepository.caseView.userId()).isEqualTo(9921L);
        assertThat(riskRepository.caseView.bizType()).isEqualTo("KYC_REVIEW");
        assertThat(riskRepository.caseView.decision()).isEqualTo("REVIEW");
        assertThat(riskRepository.caseView.status()).isEqualTo("REVIEWING");
        assertThat(riskRepository.caseView.ruleCodes()).isEqualTo("KYC_REVIEW_MANUAL");
    }

    @Test
    void archivedWithdrawRuleCannotBeReactivated() {
        authenticateK3();
        ApiResult<RiskRuleView> result = service.updateWithdrawRuleState(
                "WR-06",
                "idem-k",
                new RiskRuleStatusRequest("active", 0L, "reopen archived rule", "spoofed-operator"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void conditionUpdateAuditsWithdrawRuleChange() {
        authenticateK3();
        ApiResult<RiskRuleView> result = service.updateWithdrawRuleCondition(
                "WR-01",
                "idem-k",
                new RiskRuleConditionRequest("单笔 >= $2,000", "freeze", 80, 0L,
                        "tighten amount line", "spoofed-operator"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conditionText()).isEqualTo("单笔 >= $2,000");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K3_WITHDRAW_RULE_CONDITION_CHANGED");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("authenticated-risk-lead");
        assertThat(result.getData().action()).isEqualTo("freeze");
        assertThat(result.getData().priority()).isEqualTo(80);
        assertThat(result.getData().version()).isEqualTo(1L);
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K3_RULE_CONFIG:WR-01"),
                org.mockito.ArgumentMatchers.eq("idem-k"),
                org.mockito.ArgumentMatchers.anyString(), any(), any());
    }

    @Test
    void conditionUpdateRejectsFreeTextWithdrawRuleCondition() {
        authenticateK3();
        ApiResult<RiskRuleView> result = service.updateWithdrawRuleCondition(
                "WR-02",
                "idem-k",
                new RiskRuleConditionRequest("风险太高就延迟", "delay", 50, 0L,
                        "bad free text rule condition", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("RULE_CONDITION_INVALID");
    }

    @Test
    void createWithdrawRuleAcceptsStructuredRuleCondition() {
        authenticateK3();
        ApiResult<RiskRuleView> result = service.createWithdrawRule(
                "idem-k-create",
                new RiskRuleCreateRequest("速度", "24h >= 4 笔 或 >= $8,000", "delay", 60,
                        "structured velocity rule", "spoofed-operator"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ruleId()).startsWith("WR-C");
        assertThat(result.getData().conditionText()).isEqualTo("24h >= 4 笔 或 >= $8,000");
        assertThat(result.getData().priority()).isEqualTo(60);
        assertThat(result.getData().version()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isEqualTo("authenticated-risk-lead");
    }

    @Test
    void createWithdrawRuleAcceptsExactAddressSourceAndRejectsThresholdOutsideZeroToOne() {
        authenticateK3();

        ApiResult<RiskRuleView> accepted = service.createWithdrawRule(
                "idem-k-address-source",
                new RiskRuleCreateRequest("地址信誉",
                        "addressReputationSource=third-party; addressReputationLowThreshold=0.4",
                        "freeze", 61, "configure authoritative chain reputation source", "spoofed-operator"));
        ApiResult<RiskRuleView> rejected = service.createWithdrawRule(
                "idem-k-address-threshold-invalid",
                new RiskRuleCreateRequest("地址信誉",
                        "addressReputationSource=combined; addressReputationLowThreshold=1.01",
                        "freeze", 62, "reject invalid chain reputation threshold", "spoofed-operator"));

        assertThat(accepted.getCode()).isZero();
        assertThat(accepted.getData().conditionText()).isEqualTo(
                "addressReputationSource=third-party; addressReputationLowThreshold=0.4");
        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(rejected.getMessage()).isEqualTo("RULE_CONDITION_INVALID");
    }

    @Test
    void createWithdrawRuleRejectsDuplicatePriorityWithinTheSameDimension() {
        authenticateK3();

        ApiResult<RiskRuleView> result = service.createWithdrawRule(
                "idem-k-create-priority-conflict",
                new RiskRuleCreateRequest("金额", "单笔 >= $3,000", "freeze", 50,
                        "avoid ambiguous primary match", "spoofed-operator"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("K3_RULE_PRIORITY_CONFLICT");
    }

    @Test
    void dryRunWithdrawRulesEvaluatesBusinessWithdrawalsWithoutWritingProductionHits() {
        authenticateK3();
        int hitsBefore = riskRepository.withdrawHits.size();
        ApiResult<Map<String, Object>> result = service.dryRunWithdrawRules(
                "idem-k3-dryrun",
                new RiskRuleDryRunRequest("verify K3 sandbox evaluation", "spoofed-operator"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("status")).isEqualTo("COMPLETED");
        assertThat(result.getData().get("evaluatedWithdrawals")).isEqualTo(2);
        assertThat(result.getData().get("activeRules")).isEqualTo(4);
        assertThat(result.getData().get("hitCount")).isEqualTo(4);
        assertThat(riskRepository.withdrawHits).hasSize(hitsBefore);
        assertThat(result.getData().get("primaryRuleIdsByWithdrawal")).asString()
                .contains("WD-K3-1", "WR-01", "WD-K3-2", "WR-04");
        @SuppressWarnings("unchecked")
        List<RiskRouteCountView> routes = (List<RiskRouteCountView>) result.getData().get("routeCounts");
        assertThat(routes).anySatisfy(route -> {
            assertThat(route.routeKey()).isEqualTo("manual");
            assertThat(route.count()).isEqualTo(1L);
        }).anySatisfy(route -> {
            assertThat(route.routeKey()).isEqualTo("freeze");
            assertThat(route.count()).isEqualTo(1L);
        });
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void k3ComparatorsKeepStrictAndInclusiveBoundariesDistinct() throws Exception {
        var method = OpsRiskService.class.getDeclaredMethod(
                "compareRuleValue", BigDecimal.class, BigDecimal.class, String.class);
        method.setAccessible(true);

        assertThat(method.invoke(service, BigDecimal.TEN, BigDecimal.TEN, "> 10")).isEqualTo(false);
        assertThat(method.invoke(service, BigDecimal.TEN, BigDecimal.TEN, ">= 10")).isEqualTo(true);
        assertThat(method.invoke(service, BigDecimal.TEN, BigDecimal.TEN, "< 10")).isEqualTo(false);
        assertThat(method.invoke(service, BigDecimal.TEN, BigDecimal.TEN, "<= 10")).isEqualTo(true);
    }

    @Test
    void k3DryRunCacheKeepsBase58CaseButNormalizesEvmHexCase() throws Exception {
        var method = OpsRiskService.class.getDeclaredMethod(
                "addressReputationCacheKey", String.class, String.class);
        method.setAccessible(true);

        assertThat(method.invoke(service, "USDT-TRC20", "TRAbC123"))
                .isNotEqualTo(method.invoke(service, "USDT-TRC20", "TRaBc123"));
        assertThat(method.invoke(service, "USDT-ERC20", "0xAbC1230000000000000000000000000000000000"))
                .isEqualTo(method.invoke(service, "USDT-ERC20", "0xaBc1230000000000000000000000000000000000"));
    }

    @Test
    void k3StateMachineRejectsIllegalJumpsAndStaleVersions() {
        authenticateK3();

        ApiResult<RiskRuleView> draftToPaused = service.updateWithdrawRuleState(
                "WR-DRAFT", "idem-illegal-state",
                new RiskRuleStatusRequest("paused", 0L, "illegal draft pause", "ignored"));
        ApiResult<RiskRuleView> activeToArchived = service.updateWithdrawRuleState(
                "WR-01", "idem-illegal-archive",
                new RiskRuleStatusRequest("archived", 0L, "illegal active archive", "ignored"));
        ApiResult<RiskRuleView> stale = service.updateWithdrawRuleState(
                "WR-01", "idem-stale-state",
                new RiskRuleStatusRequest("paused", 7L, "stale version change", "ignored"));

        assertThat(draftToPaused.getCode()).isEqualTo(409);
        assertThat(activeToArchived.getCode()).isEqualTo(409);
        assertThat(stale.getCode()).isEqualTo(409);
        assertThat(stale.getMessage()).isEqualTo("K3_RULE_CONCURRENT_UPDATE");

        ArgumentCaptor<AuditLogWriteRequest> rejectedAudit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(3)).recordRequiredInNewTransaction(rejectedAudit.capture());
        assertThat(rejectedAudit.getAllValues()).allSatisfy(audit -> {
            assertThat(audit.getAction()).isEqualTo("K3_WITHDRAW_RULE_WRITE_REJECTED");
            assertThat(audit.getResult()).isEqualTo("REJECTED");
            assertThat(audit.getActorUsername()).isEqualTo("authenticated-risk-lead");
            assertThat(audit.getDetail()).isInstanceOfSatisfying(Map.class, detail -> {
                assertThat(detail.get("reasonCode")).isIn(
                        "K3_RULE_TRANSITION_INVALID", "K3_RULE_CONCURRENT_UPDATE");
                assertThat(detail.get("businessDataChanged")).isEqualTo(false);
                assertThat(detail.get("idempotencyKey")).isNotNull();
            });
        });
    }

    @Test
    void dryRunFailsClosedInsteadOfCompletingWithInventedThirdPartyScores() {
        authenticateK3();
        RiskRuleView thirdParty = new RiskRuleView(
                "WR-THIRD-DRY", "地址信誉",
                "addressReputationSource=third-party; addressReputationLowThreshold=0.4",
                "freeze", "active", false, 77, 0L,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
        riskRepository.rules.add(thirdParty);
        when(chainAddressReputationGateway.score(anyString(), anyString()))
                .thenThrow(new IllegalStateException("K3_ADDRESS_REPUTATION_UNAVAILABLE"));
        try {
            ApiResult<Map<String, Object>> result = service.dryRunWithdrawRules(
                    "idem-k3-dryrun-provider-down",
                    new RiskRuleDryRunRequest("prove third party dry run fails closed", "spoofed-operator"));

            assertThat(result.getCode()).isEqualTo(503);
            assertThat(result.getMessage()).isEqualTo("K3_DRY_RUN_ADDRESS_REPUTATION_UNAVAILABLE");
            assertThat(result.getData()).isNull();
        } finally {
            riskRepository.rules.remove(thirdParty);
        }
    }

    @Test
    void k3AllowsOnlyDocumentedStateTransitionsAndIncrementsVersion() {
        authenticateK3();

        ApiResult<RiskRuleView> paused = service.updateWithdrawRuleState(
                "WR-01", "idem-pause-state",
                new RiskRuleStatusRequest("paused", 0L, "pause during risk review", "ignored"));
        ApiResult<RiskRuleView> archived = service.updateWithdrawRuleState(
                "WR-01", "idem-archive-state",
                new RiskRuleStatusRequest("archived", 1L, "archive retired risk rule", "ignored"));

        assertThat(paused.getCode()).isZero();
        assertThat(paused.getData().version()).isEqualTo(1L);
        assertThat(archived.getCode()).isZero();
        assertThat(archived.getData().state()).isEqualTo("archived");
        assertThat(archived.getData().version()).isEqualTo(2L);
    }

    @Test
    void k3RejectsConfigurablePassOutOfRangePriorityAndReasonOutsideEightToTwoHundredChars() {
        authenticateK3();

        ApiResult<RiskRuleView> pass = service.createWithdrawRule(
                "idem-pass", new RiskRuleCreateRequest(
                        "金额", "单笔 >= $1,000", "pass", 10,
                        "pass must never be configurable", "ignored"));
        ApiResult<RiskRuleView> shortReason = service.createWithdrawRule(
                "idem-short", new RiskRuleCreateRequest(
                        "金额", "单笔 >= $1,000", "manual", 10, "short", "ignored"));
        ApiResult<RiskRuleView> longReason = service.createWithdrawRule(
                "idem-long", new RiskRuleCreateRequest(
                        "金额", "单笔 >= $1,000", "manual", 10, "x".repeat(201), "ignored"));
        ApiResult<RiskRuleView> outOfRangePriority = service.createWithdrawRule(
                "idem-priority", new RiskRuleCreateRequest(
                        "金额", "单笔 >= $1,000", "manual", 101,
                        "priority must match the one through one hundred UI contract", "ignored"));

        assertThat(pass.getMessage()).isEqualTo("RULE_ACTION_INVALID");
        assertThat(shortReason.getMessage()).isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
        assertThat(longReason.getMessage()).isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
        assertThat(outOfRangePriority.getMessage()).isEqualTo("K3_RULE_PRIORITY_INVALID");
    }

    @Test
    void k3RequiresAuthenticatedActorInsteadOfTrustingRequestOperator() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        ApiResult<RiskRuleView> result = service.createWithdrawRule(
                "idem-no-auth", new RiskRuleCreateRequest(
                        "金额", "单笔 >= $1,000", "manual", 10,
                        "authenticated actor required", "spoofed-superadmin"));

        assertThat(result.getCode()).isEqualTo(403);
        verify(auditLogService, never()).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void withdrawRuleOverviewReturnsPagedRulesAndHits() {
        ApiResult<Map<String, Object>> result = service.withdrawRuleOverview(
                new RiskRuleOverviewQueryRequest(2, 2, 2, 1, "delay"));

        assertThat(result.getCode()).isZero();
        PageResult<?> rules = (PageResult<?>) result.getData().get("rules");
        PageResult<?> hits = (PageResult<?>) result.getData().get("hits");
        assertThat(rules.getTotal()).isEqualTo(6);
        assertThat(rules.getPageNum()).isEqualTo(2);
        assertThat(rules.getPageSize()).isEqualTo(2);
        assertThat(rules.getRecords()).hasSize(2);
        assertThat(hits.getTotal()).isEqualTo(2);
        assertThat(hits.getPageNum()).isEqualTo(2);
        assertThat(hits.getPageSize()).isEqualTo(1);
        assertThat(hits.getRecords()).hasSize(1);
    }

    @Test
    void withdrawRuleOverviewDoesNotInventDefaultDimensionsWithoutDbRules() {
        riskRepository.rules.clear();

        ApiResult<Map<String, Object>> result = service.withdrawRuleOverview();

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        List<RiskRuleDimensionView> dimensions = (List<RiskRuleDimensionView>) result.getData().get("dimensions");
        assertThat(dimensions).isEmpty();
    }

    @Test
    void withdrawRuleOverviewProjectsOnlyActiveRulesIntoCurrentDimensionCards() {
        ApiResult<Map<String, Object>> result = service.withdrawRuleOverview();

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        List<RiskRuleDimensionView> dimensions = (List<RiskRuleDimensionView>) result.getData().get("dimensions");
        assertThat(dimensions)
                .extracting(RiskRuleDimensionView::ruleId)
                .containsExactly("WR-01", "WR-02", "WR-03", "WR-04")
                .doesNotContain("WR-DRAFT", "WR-06");
    }

    @Test
    void arbitrageOverviewRemovesRetiredHoldingGateAndUsesCurrentTradeInEvidenceModel() {
        riskRepository.arbitrageParams.add(new RiskArbitrageParamView(
                "minHoldingMonths", "最小持仓月份", "6", "retired", "retired"));

        ApiResult<Map<String, Object>> result = service.arbitrageOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).doesNotContainKey("minHoldingMonths");
        @SuppressWarnings("unchecked")
        List<RiskArbitrageParamView> params = (List<RiskArbitrageParamView>) result.getData().get("params");
        assertThat(params).extracting(RiskArbitrageParamView::key).doesNotContain("minHoldingMonths");
        @SuppressWarnings("unchecked")
        List<ffdd.opsconsole.risk.domain.RiskArbitrageViewGroup> views =
                (List<ffdd.opsconsole.risk.domain.RiskArbitrageViewGroup>) result.getData().get("views");
        var tradeIn = views.stream().filter(view -> "tradein".equals(view.key())).findFirst().orElseThrow();
        assertThat(tradeIn.sub()).contains("高频下架置换", "礼金/返佣叠加");
        assertThat(tradeIn.sub()).doesNotContain("最短持有", "残值 $0");
        assertThat(tradeIn.head()).doesNotContain("层数命中", "动作");
        assertThat(riskRepository.e3TradeinProjectionRefreshes).isEqualTo(1);
    }

    @Test
    void arbitrageOverviewProjectsAuthoritativeH2CyclesIntoDurableSignalAndA4Event() {
        riskRepository.trialCycleDetections.add(
                new RiskOpsRepository.TrialCycleDetection("K2-H2-U1", 1L, "CL-318", 3));

        ApiResult<Map<String, Object>> result = service.arbitrageOverview();

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.k2Signals)
                .extracting(K2Signal::userId, K2Signal::signalType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, "risk.trial_cycle_detected"));
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("RISK_ARBITRAGE_ROW"),
                org.mockito.ArgumentMatchers.eq("K2-H2-U1"),
                org.mockito.ArgumentMatchers.eq("risk.trial_cycle_detected"),
                any());
    }

    @Test
    void arbitrageActionUpdatesDispositionAndAudits() {
        authenticateK2Admin();
        ApiResult<RiskArbitrageRowView> result = service.executeArbitrageAction(
                "T-318",
                "mark",
                "idem-k",
                new RiskArbitrageActionRequest("linked account evidence", "superadmin", 0L, 0L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().disposition()).isEqualTo("已标记套利");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K2_ARBITRAGE_MARKED");
        assertThat(captor.getValue().getDetail().toString()).contains("before", "after", "idempotencyKey");
        assertThat(riskRepository.k2Signals)
                .extracting(K2Signal::signalType)
                .containsExactly("risk.arbitrage_suspected", "risk.arbitrage_suspected");
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("RISK_ARBITRAGE_ROW"),
                org.mockito.ArgumentMatchers.eq("T-318"),
                org.mockito.ArgumentMatchers.eq("risk.arbitrage_suspected"),
                any());
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K2_ACTION:T-318"),
                org.mockito.ArgumentMatchers.eq("idem-k"), anyString(),
                org.mockito.ArgumentMatchers.eq(ApiResult.class), any());
    }

    @Test
    void arbitrageActionRequiresTheExactAuthorityAndUsesAuthenticatedActor() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-k2-flagger", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_row_flag")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<RiskArbitrageRowView> denied = service.executeArbitrageAction(
                    "T-318", "freeze-cluster", "idem-k2-denied",
                    new RiskArbitrageActionRequest("cannot freeze with flag permission", "spoofed", 0L, 0L));
            assertThat(denied.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());

            ApiResult<RiskArbitrageRowView> allowed = service.executeArbitrageAction(
                    "T-318", "mark", "idem-k2-allowed",
                    new RiskArbitrageActionRequest("mark the verified arbitrage account", "spoofed", 0L, 0L));
            assertThat(allowed.getCode()).isZero();
            verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                    "risk-k2-flagger".equals(audit.getActorUsername())));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void arbitrageFreezeUsesK1StateAndBothProjectionVersions() {
        riskRepository.multiAccountClusters.put("CL-318", "flagged");
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "k2-approver", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_row_freeze"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_operation_approve")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<RiskArbitrageRowView> result = service.executeArbitrageAction(
                    "T-318", "freeze-cluster", "idem-k2-freeze",
                    new RiskArbitrageActionRequest("freeze after K1 suspicious confirmation", "spoofed", 0L, 0L));
            assertThat(result.getCode()).isZero();
            assertThat(riskRepository.multiAccountClusters.get("CL-318")).isEqualTo("frozen");
            verify(userAccountControlFacade).freezeActiveUsersByUserNos(
                    List.of("U00000001", "U00000002"),
                    "freeze after K1 suspicious confirmation", "k2-approver", "CL-318");
            verify(auditLogService, times(2)).recordRequired(any(AuditLogWriteRequest.class));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void disposedArbitrageRowRejectsRepeatAction() {
        authenticateK2Admin();
        riskRepository.updateArbitrageDisposition("T-318", 0L, "已标记套利");

        ApiResult<RiskArbitrageRowView> result = service.executeArbitrageAction(
                "T-318",
                "freeze-cluster",
                "idem-k",
                new RiskArbitrageActionRequest("repeat action", "superadmin", 1L, 0L));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void arbitrageParamAcceptsStructuredThresholdValue() {
        authenticateK2Admin();
        ApiResult<RiskArbitrageParamView> result = service.updateArbitrageParam(
                "welcomeGiftAnomalyThreshold",
                "idem-k2-param",
                new RiskArbitrageParamUpdateRequest(">= 2 笔 / 实体", "tighten welcome gift anomaly line", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo(">= 2 笔 / 实体");
        assertThat(riskRepository.arbitrageParam("welcomeGiftAnomalyThreshold")).isEqualTo(">= 2 笔 / 实体");
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K2_PARAM:welcomeGiftAnomalyThreshold"),
                org.mockito.ArgumentMatchers.eq("idem-k2-param"), anyString(),
                org.mockito.ArgumentMatchers.eq(ApiResult.class), any());
    }

    @Test
    void arbitrageParamRejectsFreeTextThresholdValue() {
        authenticateK2Admin();
        ApiResult<RiskArbitrageParamView> result = service.updateArbitrageParam(
                "welcomeGiftAnomalyThreshold",
                "idem-k2-param",
                new RiskArbitrageParamUpdateRequest("同一人多领就拦", "bad free text threshold", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void otpGateParamRejectsValueBelowSafeMinimum() {
        authenticateK2Admin();
        ApiResult<RiskArbitrageParamView> result = service.updateArbitrageParam(
                "otpGate.otpTtlSeconds",
                "idem-k2-otp",
                new RiskArbitrageParamUpdateRequest("10", "unsafe ttl", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void otpGateCooldownWritesCanonicalAuthRiskConfig() {
        authenticateK2Admin();
        ApiResult<RiskArbitrageParamView> result = service.updateArbitrageParam(
                "otpGate.resendSeconds",
                "idem-k2-otp-cooldown",
                new RiskArbitrageParamUpdateRequest("90", "tighten otp cooldown", "superadmin"));

        assertThat(result.getCode()).isZero();
        verify(configFacade).upsertAdminValue(
                "auth.risk.otp_cooldown_seconds", "90", "NUMBER", "auth-risk", "K2 OTP gate canonical configuration");
    }

    @Test
    void arbitrageParamRejectsStaleProjectionVersion() {
        authenticateK2Admin();
        ApiResult<RiskArbitrageParamView> first = service.updateArbitrageParam(
                "welcomeGiftAnomalyThreshold", "idem-k2-param-v1",
                new RiskArbitrageParamUpdateRequest(
                        ">= 3 笔 / 实体", "first concurrent parameter update", "spoofed", 0L));
        ApiResult<RiskArbitrageParamView> stale = service.updateArbitrageParam(
                "welcomeGiftAnomalyThreshold", "idem-k2-param-stale",
                new RiskArbitrageParamUpdateRequest(
                        ">= 4 笔 / 实体", "stale concurrent parameter update", "spoofed", 0L));

        assertThat(first.getCode()).isZero();
        assertThat(first.getData().version()).isEqualTo(1L);
        assertThat(stale.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(stale.getMessage()).isEqualTo("K2_PARAM_CONCURRENT_UPDATE");
    }

    @Test
    void k2MutationsFailClosedWithoutAuthenticatedOperator() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        ApiResult<RiskArbitrageParamView> param = service.updateArbitrageParam(
                "welcomeGiftAnomalyThreshold", "idem-k2-no-auth-param",
                new RiskArbitrageParamUpdateRequest(
                        ">= 3 笔 / 实体", "unauthenticated parameter attempt", "spoofed", 0L));
        ApiResult<RiskArbitrageRowView> row = service.executeArbitrageAction(
                "T-318", "mark", "idem-k2-no-auth-row",
                new RiskArbitrageActionRequest("unauthenticated row attempt", "spoofed", 0L, 0L));

        assertThat(param.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(row.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        verify(auditLogService, org.mockito.Mockito.never()).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void otpGateAuditCapturesCanonicalValueBeforePersistence() {
        authenticateK2Admin();
        java.util.concurrent.atomic.AtomicReference<String> canonical =
                new java.util.concurrent.atomic.AtomicReference<>("60");
        when(configFacade.activeValue("auth.risk.otp_cooldown_seconds"))
                .thenAnswer(invocation -> Optional.of(canonical.get()));
        doAnswer(invocation -> {
            canonical.set(invocation.getArgument(1));
            return null;
        }).when(configFacade).upsertAdminValue(
                org.mockito.ArgumentMatchers.eq("auth.risk.otp_cooldown_seconds"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("NUMBER"),
                org.mockito.ArgumentMatchers.eq("auth-risk"),
                org.mockito.ArgumentMatchers.eq("K2 OTP gate canonical configuration"));

        ApiResult<RiskArbitrageParamView> result = service.updateArbitrageParam(
                "otpGate.resendSeconds",
                "idem-k2-otp-audit",
                new RiskArbitrageParamUpdateRequest("61", "verify otp audit snapshots", "spoofed"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) captor.getValue().getDetail();
        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) detail.get("before");
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) detail.get("after");
        assertThat(before.get("value")).isEqualTo("60");
        assertThat(after.get("value")).isEqualTo("61");
    }

    private void authenticateK2Admin() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        "superadmin", "n/a", List.of(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_write"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_row_flag"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_row_freeze"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_row_blockgift"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_row_boardflag"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_operation_approve"))));
    }

    @Test
    void scoringWeightsMustSumTo100() {
        RiskScoringModelDraftRequest invalid = canonicalDraft("rebalance scoring weights");
        invalid = new RiskScoringModelDraftRequest(
                invalid.expectedVersion(),
                Map.of(
                        "multiAccount", 25, "arbitrage", 20, "kycStatus", 20,
                        "withdrawVelocity", 15, "accountAge", 10, "anomalyBehavior", 11),
                invalid.inputSources(), invalid.lowMax(), invalid.highMin(), invalid.autoEscalateScore(),
                invalid.reason(), invalid.operator());
        ApiResult<Map<String, Object>> result = service.saveScoringModelDraft("idem-k", invalid);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void scoringSourceIsSavedInsideVersionedDraft() {
        RiskScoringModelDraftRequest base = canonicalDraft("disable source for incident");
        Map<String, Boolean> sources = new LinkedHashMap<>(base.inputSources());
        sources.put("arbitrage", false);
        ApiResult<Map<String, Object>> result = service.saveScoringModelDraft(
                "idem-k", new RiskScoringModelDraftRequest(
                        base.expectedVersion(), base.weights(), sources, base.lowMax(), base.highMin(),
                        base.autoEscalateScore(), base.reason(), base.operator()));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.draftScoringModel().orElseThrow().inputSources().get("arbitrage")).isFalse();
    }

    @Test
    void scoringDraftAcceptsPrdRatioWeightsAndVersionsSubScoreMappings() {
        authenticateK4Admin(true);
        Map<String, Integer> mappings = new LinkedHashMap<>(K4RiskScorer.DEFAULT_MAPPINGS);
        mappings.put("withdraw.highScore", 88);
        RiskScoringModelDraftRequest request = new RiskScoringModelDraftRequest(
                0L,
                Map.of(
                        "multiAccount", new BigDecimal("0.251"), "arbitrage", new BigDecimal("0.199"),
                        "kycStatus", new BigDecimal("0.20"), "withdrawVelocity", new BigDecimal("0.15"),
                        "accountAge", new BigDecimal("0.10"), "anomalyBehavior", new BigDecimal("0.10")),
                canonicalDraft("ratio weights and mappings").inputSources(), mappings,
                40, 70, 85, "ratio weights and mappings", "spoofed");

        ApiResult<Map<String, Object>> result = service.saveScoringModelDraft("idem-k4-ratio", request);

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.draftScoringModel().orElseThrow().weights().get("multiAccount")).isEqualTo(25);
        assertThat(riskRepository.draftScoringModel().orElseThrow().scoreMappings().get("withdraw.highScore")).isEqualTo(88);
    }

    @Test
    void scoringDraftRejectsSubScoresThatDecreaseAsRiskSeverityIncreases() {
        RiskScoringModelDraftRequest base = canonicalDraft("reject reversed sub scores");
        Map<String, Integer> mappings = new LinkedHashMap<>(base.scoreMappings());
        mappings.put("multiAccount.mediumScore", 90);
        mappings.put("multiAccount.highScore", 60);
        RiskScoringModelDraftRequest invalid = new RiskScoringModelDraftRequest(
                base.expectedVersion(), base.weights(), base.inputSources(), mappings,
                base.lowMax(), base.highMin(), base.autoEscalateScore(), base.reason(), base.operator());

        assertThat(service.saveScoringModelDraft("idem-k4-reversed-mapping", invalid).getCode()).isEqualTo(422);
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
    }

    @Test
    void scoringDraftKeepsSingleArbitrageBranchReachable() {
        RiskScoringModelDraftRequest base = canonicalDraft("reject unreachable single arbitrage branch");
        Map<String, Integer> mappings = new LinkedHashMap<>(base.scoreMappings());
        mappings.put("arbitrage.repeatMin", 1);
        RiskScoringModelDraftRequest invalid = new RiskScoringModelDraftRequest(
                base.expectedVersion(), base.weights(), base.inputSources(), mappings,
                base.lowMax(), base.highMin(), base.autoEscalateScore(), base.reason(), base.operator());

        assertThat(service.saveScoringModelDraft("idem-k4-repeat-min", invalid).getCode()).isEqualTo(422);
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
    }

    @Test
    void scoringDraftRejectsIncompleteSubScoreMappingSnapshot() {
        RiskScoringModelDraftRequest base = canonicalDraft("missing mapping snapshot");
        Map<String, Integer> mappings = new LinkedHashMap<>(base.scoreMappings());
        mappings.remove("withdraw.largeAmountUsd");
        RiskScoringModelDraftRequest invalid = new RiskScoringModelDraftRequest(
                base.expectedVersion(), base.weights(), base.inputSources(), mappings,
                base.lowMax(), base.highMin(), base.autoEscalateScore(), base.reason(), base.operator());

        assertThat(service.saveScoringModelDraft("idem-k4-mapping", invalid).getCode()).isEqualTo(422);
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
    }

    @Test
    void scoringSourceRejectsMissingCanonicalDimension() {
        RiskScoringModelDraftRequest base = canonicalDraft("invalid source attempt");
        Map<String, Boolean> sources = new LinkedHashMap<>(base.inputSources());
        sources.remove("arbitrage");
        ApiResult<Map<String, Object>> result = service.saveScoringModelDraft(
                "idem-k", new RiskScoringModelDraftRequest(
                        base.expectedVersion(), base.weights(), sources, base.lowMax(), base.highMin(),
                        base.autoEscalateScore(), base.reason(), base.operator()));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void scoringOverviewReturnsPagedManualOverrides() {
        riskRepository.addScoreOverride(new RiskScoreOverrideView("usr_a", 80, 35, "false positive", "risklead", "5/20", true));
        riskRepository.addScoreOverride(new RiskScoreOverrideView("usr_b", 40, 75, "raise risk", "risklead", "5/21", true));
        riskRepository.addScoreOverride(new RiskScoreOverrideView("usr_c", 55, 55, "closed", "risklead", "5/22", false));

        ApiResult<Map<String, Object>> result = service.scoringOverview(new RiskScoringOverviewQueryRequest(2, 2));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("overrides")).isInstanceOf(PageResult.class);
        PageResult<?> overrides = (PageResult<?>) result.getData().get("overrides");
        assertThat(overrides.getTotal()).isEqualTo(3);
        assertThat(overrides.getPageNum()).isEqualTo(2);
        assertThat(overrides.getPageSize()).isEqualTo(2);
        assertThat(overrides.getRecords()).hasSize(1);
        assertThat(result.getData().get("overrideActive")).isEqualTo(2L);
    }

    @Test
    void scoreUserSearchReturnsBackendOptionsForCombobox() {
        ApiResult<List<RiskScoreUserSearchView>> result = service.searchScoreUsers("55", 8);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).hasSize(1);
        RiskScoreUserSearchView option = result.getData().get(0);
        assertThat(option.userNo()).isEqualTo("usr_55B1");
        assertThat(option.label()).contains("usr_55B1");
        assertThat(option.effectiveScore()).isEqualTo(91);
        assertThat(option.bandLabel()).isEqualTo("高风险");
    }

    @Test
    void scoreOverrideUpdatesUserAndAudits() {
        ApiResult<RiskScoreUserView> result = service.overrideScore(
                "usr_55B1",
                "idem-k",
                new RiskScoreOverrideRequest(35, 0L, "manual false positive", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().effectiveScore()).isEqualTo(35);
        assertThat(result.getData().overridden()).isTrue();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K4_SCORE_OVERRIDDEN");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void highScoreOverrideCreatesK5ReviewTicket() {
        ApiResult<RiskScoreUserView> result = service.overrideScore(
                "usr_55B1",
                "idem-k-high",
                new RiskScoreOverrideRequest(90, 0L, "manual escalation into kyc review", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().effectiveScore()).isEqualTo(90);
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        assertThat(riskRepository.kycTicketTypeByUser("usr_55B1")).isEqualTo("风险分触发");
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(
                audit -> "K4_SCORE_OVERRIDDEN".equals(audit.getAction())));
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(
                audit -> "K5_KYC_REVIEW_TRIGGERED_BY_SCORE".equals(audit.getAction())));
    }

    @Test
    void highScoreOverrideDoesNotDuplicateOpenK5ReviewTicket() {
        riskRepository.createManualKycReviewTicket("KR-OPEN", "usr_55B1", "already open review ticket", "system");

        ApiResult<RiskScoreUserView> result = service.overrideScore(
                "usr_55B1",
                "idem-k-high-duplicate",
                new RiskScoreOverrideRequest(90, 0L, "manual escalation into kyc review", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        assertThat(riskRepository.kycTicketVersions.get("KR-OPEN")).isEqualTo(1L);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).recordRequired(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLogWriteRequest::getAction)
                .containsExactlyInAnyOrder("K4_SCORE_OVERRIDDEN", "K5_KYC_REVIEW_SCORE_TRIGGER_MERGED");
    }

    @Test
    void multiAccountParamUpdatesBackendStateAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateMultiAccountParam(
                "maxAccountsPerDevice",
                "idem-k1",
                new RiskParamUpdateRequest("4", "tighten multi account threshold", "spoofed-operator"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.multiAccountParam("maxAccountsPerDevice")).isEqualTo("4");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K1_MULTI_ACCOUNT_PARAM_CHANGED");
        assertThat(captor.getValue().getDetail()).isInstanceOfSatisfying(Map.class, detail -> {
            assertThat(detail.get("before")).isEqualTo(Map.of(
                    "exists", true, "key", "maxAccountsPerDevice", "value", "2"));
            assertThat(detail.get("after")).isEqualTo(Map.of(
                    "exists", true, "key", "maxAccountsPerDevice", "value", "4"));
        });
    }

    @Test
    void k4DraftUsesTrueIdempotencyAuthenticatedActorAndRequiredAudit() {
        authenticateK4Admin(false);
        RiskScoringModelDraftRequest request = new RiskScoringModelDraftRequest(
                0L,
                Map.of(
                        "multiAccount", 25,
                        "arbitrage", 20,
                        "kycStatus", 20,
                        "withdrawVelocity", 15,
                        "accountAge", 10,
                        "anomalyBehavior", 10),
                Map.of(
                        "multiAccount", true,
                        "arbitrage", true,
                        "kycStatus", true,
                        "withdrawVelocity", true,
                        "accountAge", true,
                        "anomalyBehavior", true),
                40,
                70,
                85,
                "save canonical K4 draft",
                "spoofed-operator");

        ApiResult<Map<String, Object>> result = service.saveScoringModelDraft("idem-k4-draft", request);

        assertThat(result.getCode()).isZero();
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K4_MODEL_DRAFT"),
                org.mockito.ArgumentMatchers.eq("idem-k4-draft"),
                anyString(), any(), any());
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K4_MODEL_DRAFT_SAVED");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("authenticated-risk-lead");
        assertThat(captor.getValue().getDetail()).isInstanceOfSatisfying(Map.class, detail ->
                assertThat(detail).containsKeys("before", "after", "reason", "idempotencyKey"));
    }

    @Test
    void k4PublishRequiresAuthoritativeSuperAdminAndArchivesPreviousVersion() {
        authenticateK4Admin(false);
        riskRepository.kycReviewParams.put("reviewTriggerScore", ">= 80");
        riskRepository.saveScoringModelDraft(0L, canonicalDraft("prepare publish"), "authenticated-risk-lead");
        when(superAdminAuthorization.isSuperAdmin(any())).thenReturn(false);

        ApiResult<Map<String, Object>> denied = service.publishScoringModel(
                "idem-k4-publish-denied",
                new RiskScoringModelPublishRequest(0L, "publish risk scoring model", "spoofed"));

        assertThat(denied.getCode()).isEqualTo(403);
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));

        when(superAdminAuthorization.isSuperAdmin(any())).thenReturn(true);
        long draftVersion = riskRepository.draftScoringModel().orElseThrow().rowVersion();
        ApiResult<Map<String, Object>> published = service.publishScoringModel(
                "idem-k4-publish-ok",
                new RiskScoringModelPublishRequest(draftVersion, "publish risk scoring model", "spoofed"));

        assertThat(published.getCode()).isZero();
        assertThat(riskRepository.activeScoringModel().orElseThrow().version()).isEqualTo(2L);
        assertThat(riskRepository.archivedModelCount()).isEqualTo(1);
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_TRIGGERED_BY_SCORE".equals(audit.getAction())
                        && audit.getDetail().toString().contains(K4KycReviewTriggerService.SOURCE_MODEL_PUBLISH)
                        && audit.getDetail().toString().contains("idem-k4-publish-ok:usr_55B1")));
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K4_MODEL_PUBLISH"),
                org.mockito.ArgumentMatchers.eq("idem-k4-publish-ok"),
                anyString(), any(), any());
    }

    @Test
    void k4PublishRevalidatesPersistedDraftSnapshot() {
        authenticateK4Admin(true);
        when(superAdminAuthorization.isSuperAdmin(any())).thenReturn(true);
        RiskScoringModelDraftRequest base = canonicalDraft("persisted legacy draft snapshot");
        Map<String, Integer> mappings = new LinkedHashMap<>(base.scoreMappings());
        mappings.put("arbitrage.repeatMin", 1);
        riskRepository.draftScoreModel = new RiskScoreModelView(
                2L, 0L, "draft", base.weightPercentages(), base.inputSources(), mappings,
                base.lowMax(), base.highMin(), base.autoEscalateScore(), base.reason(),
                "legacy", null, "2026-07-16 10:00:00", null);

        ApiResult<Map<String, Object>> result = service.publishScoringModel(
                "idem-k4-invalid-persisted-draft",
                new RiskScoringModelPublishRequest(0L, "reject invalid legacy model draft", "spoofed"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(riskRepository.activeScoringModel().orElseThrow().version()).isEqualTo(1L);
        assertThat(riskRepository.archivedModelCount()).isZero();
        verify(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
    }

    @Test
    void k4DraftRequiresAutoEscalationAtOrAboveTheHighRiskBandBoundary() {
        authenticateK4Admin(false);
        RiskScoringModelDraftRequest base = canonicalDraft("validate escalation boundary");
        RiskScoringModelDraftRequest belowHighBand = new RiskScoringModelDraftRequest(
                base.expectedVersion(), base.weights(), base.inputSources(), base.scoreMappings(),
                41, 91, 85, base.reason(), base.operator());

        ApiResult<Map<String, Object>> rejected = service.saveScoringModelDraft(
                "idem-k4-escalate-below-high", belowHighBand);

        assertThat(rejected.getCode()).isEqualTo(422);
        assertThat(rejected.getMessage()).isEqualTo("K4_MODEL_ESCALATE_INVALID");
        assertThat(riskRepository.draftScoringModel()).isEmpty();

        RiskScoringModelDraftRequest equalToHighBand = new RiskScoringModelDraftRequest(
                base.expectedVersion(), base.weights(), base.inputSources(), base.scoreMappings(),
                41, 85, 85, base.reason(), base.operator());
        ApiResult<Map<String, Object>> accepted = service.saveScoringModelDraft(
                "idem-k4-escalate-equals-high", equalToHighBand);

        assertThat(accepted.getCode()).isZero();
        assertThat(riskRepository.draftScoringModel().orElseThrow().autoEscalateScore()).isEqualTo(85);
    }

    @Test
    void k4PublishRejectsPersistedDraftWhoseEscalationThresholdIsBelowTheHighRiskBand() {
        authenticateK4Admin(true);
        when(superAdminAuthorization.isSuperAdmin(any())).thenReturn(true);
        RiskScoringModelDraftRequest base = canonicalDraft("reject inconsistent persisted draft");
        riskRepository.draftScoreModel = new RiskScoreModelView(
                2L, 0L, "draft", base.weightPercentages(), base.inputSources(), base.scoreMappings(),
                41, 91, 85, base.reason(), "legacy", null,
                "2026-07-16 10:00:00", null);

        ApiResult<Map<String, Object>> result = service.publishScoringModel(
                "idem-k4-invalid-escalate-publish",
                new RiskScoringModelPublishRequest(0L, "reject inconsistent persisted draft", "spoofed"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("K4_MODEL_SNAPSHOT_INVALID");
        assertThat(riskRepository.activeScoringModel().orElseThrow().version()).isEqualTo(1L);
        assertThat(riskRepository.archivedModelCount()).isZero();
    }

    @Test
    void k4HistoryRestoreRejectsInconsistentThresholdsAndAcceptsTheEqualBoundary() {
        authenticateK4Admin(false);
        RiskScoringModelDraftRequest base = canonicalDraft("restore historical model boundary");
        riskRepository.historicalScoreModel = new RiskScoreModelView(
                7L, 3L, "archived", base.weightPercentages(), base.inputSources(), base.scoreMappings(),
                41, 91, 85, base.reason(), "legacy", "legacy",
                "2026-07-15 10:00:00", "2026-07-15 10:01:00");

        ApiResult<Map<String, Object>> rejected = service.restoreScoringModelDraft(
                "idem-k4-invalid-history",
                new RiskScoringModelRestoreRequest(7L, 0L, "reject inconsistent history", "spoofed"));

        assertThat(rejected.getCode()).isEqualTo(422);
        assertThat(rejected.getMessage()).isEqualTo("K4_MODEL_SNAPSHOT_INVALID");
        assertThat(riskRepository.draftScoringModel()).isEmpty();

        riskRepository.historicalScoreModel = new RiskScoreModelView(
                8L, 4L, "archived", base.weightPercentages(), base.inputSources(), base.scoreMappings(),
                41, 85, 85, base.reason(), "legacy", "legacy",
                "2026-07-15 11:00:00", "2026-07-15 11:01:00");
        ApiResult<Map<String, Object>> accepted = service.restoreScoringModelDraft(
                "idem-k4-valid-history-boundary",
                new RiskScoringModelRestoreRequest(8L, 0L, "restore equal threshold boundary", "spoofed"));

        assertThat(accepted.getCode()).isZero();
        assertThat(riskRepository.draftScoringModel().orElseThrow().bandHighMin()).isEqualTo(85);
        assertThat(riskRepository.draftScoringModel().orElseThrow().autoEscalateScore()).isEqualTo(85);
    }

    @Test
    void k4RejectsMissingVersionAndReasonsOutsideEightToTwoHundredCharacters() {
        authenticateK4Admin(false);
        RiskScoringModelDraftRequest missingVersion = canonicalDraft("12345678").withExpectedVersion(null);
        RiskScoringModelDraftRequest shortReason = canonicalDraft("1234567");
        RiskScoringModelDraftRequest longReason = canonicalDraft("x".repeat(201));

        assertThat(service.saveScoringModelDraft("idem-k4-version", missingVersion).getCode()).isEqualTo(422);
        assertThat(service.saveScoringModelDraft("idem-k4-short", shortReason).getCode()).isEqualTo(422);
        assertThat(service.saveScoringModelDraft("idem-k4-long", longReason).getCode()).isEqualTo(422);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(3)).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(audit -> {
                    assertThat(audit.getAction()).isEqualTo("K4_RISK_SCORING_WRITE_REJECTED");
                    assertThat(audit.getResourceType()).isEqualTo("RISK_SCORE_MODEL");
                    assertThat(audit.getResult()).isEqualTo("REJECTED");
                });
    }

    @Test
    void k4InvalidUserWritesAreRejectedWithUserScopedAudit() {
        authenticateK4Admin(false);

        ApiResult<RiskScoreUserView> invalidOverride = service.overrideScore(
                "usr_55B1", "idem-k4-invalid-override",
                new RiskScoreOverrideRequest(101, 0L, "invalid override score", "spoofed"));
        ApiResult<RiskScoreUserView> invalidRecompute = service.recomputeScore(
                "usr_55B1", "idem-k4-invalid-recompute",
                new ffdd.opsconsole.risk.dto.RiskScoreCommandRequest(
                        null, "missing score version", "spoofed"));

        assertThat(invalidOverride.getCode()).isEqualTo(422);
        assertThat(invalidRecompute.getCode()).isEqualTo(422);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).recordRequiredInNewTransaction(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AuditLogWriteRequest::getResourceType)
                .containsOnly("RISK_SCORE_USER");
    }

    @Test
    void k4OverrideUsesExpectedVersionAndRecomputeActuallyRebuildsCanonicalDimensions() {
        authenticateK4Admin(false);
        RiskScoreUserView before = riskRepository.findScoreUser("usr_55B1").orElseThrow();

        ApiResult<RiskScoreUserView> overridden = service.overrideScore(
                "usr_55B1",
                "idem-k4-override-versioned",
                new RiskScoreOverrideRequest(35, before.rowVersion(), "manual false positive", "spoofed"));

        assertThat(overridden.getCode()).isZero();
        assertThat(overridden.getData().effectiveScore()).isEqualTo(35);
        assertThat(overridden.getData().rowVersion()).isGreaterThan(before.rowVersion());
        assertThat(overridden.getData().asOf()).isNotBlank();

        ApiResult<RiskScoreUserView> stale = service.overrideScore(
                "usr_55B1",
                "idem-k4-override-stale",
                new RiskScoreOverrideRequest(45, before.rowVersion(), "stale concurrent override", "spoofed"));
        assertThat(stale.getCode()).isEqualTo(409);

        riskRepository.kycReviewParams.put("reviewTriggerScore", ">= 80");
        ApiResult<RiskScoreUserView> recomputed = service.recomputeScore(
                "usr_55B1",
                "idem-k4-recompute",
                new ffdd.opsconsole.risk.dto.RiskScoreCommandRequest(
                        overridden.getData().rowVersion(), "return to canonical scoring", "spoofed"));
        assertThat(recomputed.getCode()).isZero();
        assertThat(recomputed.getData().overridden()).isFalse();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_TRIGGERED_BY_SCORE".equals(audit.getAction())
                        && audit.getDetail().toString().contains(K4KycReviewTriggerService.SOURCE_SCORE_RECOMPUTE)
                        && audit.getDetail().toString().contains("idem-k4-recompute")));
        assertThat(recomputed.getData().contributions())
                .extracting(RiskScoreContributionView::dimKey)
                .containsExactlyInAnyOrder(
                        "multiAccount", "arbitrage", "kycStatus",
                        "withdrawVelocity", "accountAge", "anomalyBehavior");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventPayload = ArgumentCaptor.forClass(Map.class);
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("RISK_SCORE_USER"),
                org.mockito.ArgumentMatchers.eq("usr_55B1"),
                org.mockito.ArgumentMatchers.eq("risk.score_overridden"),
                eventPayload.capture());
        assertThat(eventPayload.getValue()).containsEntry("userId", "usr_55B1")
                .containsEntry("overrideScore", 35)
                .containsEntry("reason", "manual false positive")
                .containsEntry("operator", "authenticated-risk-lead");
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("RISK_SCORE_USER"),
                org.mockito.ArgumentMatchers.eq("usr_55B1"),
                org.mockito.ArgumentMatchers.eq("risk.score_updated"),
                eventPayload.capture());
        assertThat(eventPayload.getValue()).containsEntry("userId", "usr_55B1")
                .containsEntry("score", recomputed.getData().effectiveScore())
                .containsEntry("modelVersion", "k4-v1")
                .containsKeys("band", "changedDimensions");
    }

    @Test
    void k4BatchRecomputeUsesActiveModelAndWritesOneRequiredAudit() {
        authenticateK4Admin(false);
        riskRepository.kycReviewParams.put("reviewTriggerScore", ">= 80");

        ApiResult<Map<String, Object>> result = service.recomputeScores(
                "idem-k4-batch",
                new ffdd.opsconsole.risk.dto.RiskScoreBatchCommandRequest(
                        List.of("usr_55B1"), 1L, "batch return to canonical model", "spoofed"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("count", 1);
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(
                audit -> "K4_SCORE_BATCH_RECOMPUTED".equals(audit.getAction())));
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_TRIGGERED_BY_SCORE".equals(audit.getAction())
                        && audit.getDetail().toString().contains(K4KycReviewTriggerService.SOURCE_BATCH_RECOMPUTE)
                        && audit.getDetail().toString().contains("idem-k4-batch:usr_55B1")));
    }

    @Test
    void k4BatchRecomputeRejectsAChangedActiveModelVersionBeforeWritingScores() {
        authenticateK4Admin(false);

        ApiResult<Map<String, Object>> result = service.recomputeScores(
                "idem-k4-batch-stale",
                new ffdd.opsconsole.risk.dto.RiskScoreBatchCommandRequest(
                        List.of("usr_55B1"), 999L, "reject stale model batch", "spoofed"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("K4_MODEL_VERSION_CONFLICT");
        verify(auditLogService).recordRequiredInNewTransaction(org.mockito.ArgumentMatchers.argThat(
                audit -> "RISK_SCORE_USER_BATCH".equals(audit.getResourceType())
                        && "REJECTED".equals(audit.getResult())));
    }

    @Test
    void k4UnexpectedWriteFailureLeavesIndependentFailedAudit() {
        authenticateK4Admin(true);
        when(idempotencyService.execute(
                org.mockito.ArgumentMatchers.eq("K4_MODEL_DRAFT"), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("simulated database outage"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveScoringModelDraft(
                        "idem-k4-failed", canonicalDraft("capture unexpected failure")))
                .isInstanceOf(IllegalStateException.class);
        verify(auditLogService).recordRequiredInNewTransaction(org.mockito.ArgumentMatchers.argThat(
                audit -> "K4_RISK_SCORING_WRITE_FAILED".equals(audit.getAction())
                        && "FAILED".equals(audit.getResult())));
    }

    @Test
    void k4WriteMethodsAreTransactional() {
        assertThat(java.util.Arrays.stream(OpsRiskService.class.getDeclaredMethods())
                .filter(method -> List.of(
                        "saveScoringModelDraft", "publishScoringModel", "overrideScore", "recomputeScore")
                        .contains(method.getName()))
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList())
                .containsExactlyInAnyOrder(
                        "saveScoringModelDraft", "publishScoringModel", "overrideScore", "recomputeScore");
    }

    private RiskScoringModelDraftRequest canonicalDraft(String reason) {
        return new RiskScoringModelDraftRequest(
                0L,
                Map.of(
                        "multiAccount", 25, "arbitrage", 20, "kycStatus", 20,
                        "withdrawVelocity", 15, "accountAge", 10, "anomalyBehavior", 10),
                Map.of(
                        "multiAccount", true, "arbitrage", true, "kycStatus", true,
                        "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true),
                40, 70, 85, reason, "spoofed");
    }

    private void authenticateK4Admin(boolean superAdmin) {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "1", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k4_write"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k4_user_override"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k4_user_recompute")));
        authentication.setDetails(Map.of(
                "username", "authenticated-risk-lead",
                "subjectType", "ADMIN",
                "superAdmin", superAdmin));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void multiAccountWhitelistAuditsBeforeAndAfterSnapshotsForUpsertAndDisable() {
        String cidr = "103.86.44.0/24";

        ApiResult<Map<String, Object>> upserted = service.upsertIpWhitelist(
                "idem-k1-whitelist-upsert",
                new ffdd.opsconsole.risk.dto.RiskIpWhitelistRequest(
                        cidr, "updated shared office", "2099-12-31", "update whitelist evidence", "spoofed"));
        ApiResult<Map<String, Object>> disabled = service.disableIpWhitelist(
                "idem-k1-whitelist-disable",
                new ffdd.opsconsole.risk.dto.RiskIpWhitelistRequest(
                        cidr, null, null, "disable whitelist evidence", "spoofed"));

        assertThat(upserted.getCode()).isZero();
        assertThat(disabled.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).recordRequired(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> upsertDetail = (Map<String, Object>) captor.getAllValues().get(0).getDetail();
        assertThat(upsertDetail.get("before")).isEqualTo(Map.of(
                "exists", true,
                "cidr", cidr,
                "note", "seed whitelist",
                "expireText", "2099-12-31",
                "active", true,
                "operator", "seed-operator"));
        assertThat(upsertDetail.get("after")).isEqualTo(Map.of(
                "exists", true,
                "cidr", cidr,
                "note", "updated shared office",
                "expireText", "2099-12-31",
                "active", true,
                "operator", "system"));

        @SuppressWarnings("unchecked")
        Map<String, Object> disableDetail = (Map<String, Object>) captor.getAllValues().get(1).getDetail();
        assertThat(disableDetail.get("before")).isEqualTo(upsertDetail.get("after"));
        assertThat(disableDetail.get("after")).isEqualTo(Map.of(
                "exists", true,
                "cidr", cidr,
                "note", "updated shared office",
                "expireText", "2099-12-31",
                "active", false,
                "operator", "system"));
    }

    @Test
    void multiAccountParamRejectsUnknownKeyAndOutOfRangeOrFreeTextValues() {
        assertThat(service.updateMultiAccountParam(
                "autoFreezeHighCluster", "idem-auto-freeze",
                new RiskParamUpdateRequest("on", "try forbidden auto freeze", "superadmin")).getCode()).isEqualTo(422);
        assertThat(service.updateMultiAccountParam(
                "maxAccountsPerDevice", "idem-free-text",
                new RiskParamUpdateRequest("not-a-number", "reject free text threshold", "superadmin")).getCode()).isEqualTo(422);
        assertThat(service.updateMultiAccountParam(
                "maxAccountsPerDevice", "idem-out-of-range",
                new RiskParamUpdateRequest("6", "reject unsafe high threshold", "superadmin")).getCode()).isEqualTo(422);
    }

    @Test
    void multiAccountLinkWeightRequiresThreeWeightsSumToOne() {
        ApiResult<Map<String, Object>> result = service.updateMultiAccountParam(
                "linkWeight",
                "idem-k1",
                new RiskParamUpdateRequest("设备 0.50 · 支付 0.40 · IP 0.10", "rebalance link strength weights", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.multiAccountParam("linkWeight")).isEqualTo("设备 0.50 · 支付 0.40 · IP 0.10");
    }

    @Test
    void multiAccountLinkWeightRejectsFreeText() {
        ApiResult<Map<String, Object>> result = service.updateMultiAccountParam(
                "linkWeight",
                "idem-k1",
                new RiskParamUpdateRequest("设备优先,其他看情况", "bad free text weight", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void multiAccountOverviewReturnsPagedLists() {
        ApiResult<Map<String, Object>> result = service.multiAccountOverview(2, 1, "ip", 2, 1);

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        PageResult<Map<String, String>> clusters = (PageResult<Map<String, String>>) result.getData().get("clusters");
        @SuppressWarnings("unchecked")
        PageResult<String> whitelist = (PageResult<String>) result.getData().get("whitelist");
        assertThat(clusters.getPageNum()).isEqualTo(2);
        assertThat(clusters.getPageSize()).isEqualTo(1);
        assertThat(clusters.getTotal()).isEqualTo(2);
        assertThat(clusters.getRecords()).hasSize(1);
        assertThat(clusters.getRecords().get(0).get("layer")).isEqualTo("ip");
        assertThat(whitelist.getPageNum()).isEqualTo(2);
        assertThat(whitelist.getPageSize()).isEqualTo(1);
        assertThat(whitelist.getTotal()).isEqualTo(2);
        assertThat(whitelist.getRecords()).containsExactly("202.120.0.0/16");
    }

    @Test
    void multiAccountOverviewRejectsUnknownLayerInsteadOfSilentlyShowingAllLayers() {
        ApiResult<Map<String, Object>> result = service.multiAccountOverview(
                1, 5, "bogus-layer", null, "strength_desc", 1, 5);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("K1_CLUSTER_LAYER_FILTER_INVALID");
    }

    @Test
    void multiAccountClusterStatusRejectsUnknownCluster() {
        ApiResult<Map<String, Object>> result = service.updateMultiAccountClusterStatus(
                "CL-MISSING",
                "idem-k1",
                new RiskClusterStatusRequest("flagged", "ops review missing cluster", "superadmin", 0L));

        assertThat(result.getCode()).isEqualTo(404);
    }

    @Test
    void successfulClusterStatusRetryReplaysBeforeCheckingANewerObjectLock() {
        riskRepository.multiAccountClusters.put("CL-318", "detected");
        when(lockMapper.countActiveByTarget("K", "cluster", "CL-318")).thenReturn(0, 1);
        java.util.concurrent.atomic.AtomicReference<Object> cached = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            Object replay = cached.get();
            if (replay != null) return replay;
            Object result = ((java.util.function.Supplier<?>) invocation.getArgument(4)).get();
            cached.set(result);
            return result;
        }).when(idempotencyService).execute(anyString(), anyString(), anyString(), any(), any());
        RiskClusterStatusRequest request = new RiskClusterStatusRequest(
                "flagged", "same request retries after response loss", "superadmin", 0L);

        ApiResult<Map<String, Object>> first = service.updateMultiAccountClusterStatus(
                "CL-318", "idem-status-replay-before-lock", request);
        ApiResult<Map<String, Object>> replay = service.updateMultiAccountClusterStatus(
                "CL-318", "idem-status-replay-before-lock", request);

        assertThat(first.getCode()).isZero();
        assertThat(replay).isSameAs(first);
        assertThat(riskRepository.multiAccountClusters.get("CL-318")).isEqualTo("flagged");
        verify(lockMapper, times(1)).countActiveByTarget("K", "cluster", "CL-318");
    }

    @Test
    void multiAccountClusterRejectsSkippedStateAndActionPermissionMismatch() {
        riskRepository.multiAccountClusters.put("CL-318", "detected");
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-member", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k1_cluster_freeze"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k1_cluster_release")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<Map<String, Object>> skipped = service.updateMultiAccountClusterStatus(
                    "CL-318", "idem-skip-state",
                    new RiskClusterStatusRequest("frozen", "attempt to skip flagged state", "spoofed-operator", 0L));
            assertThat(skipped.getCode()).isEqualTo(409);

            ApiResult<Map<String, Object>> mismatched = service.updateMultiAccountClusterStatus(
                    "CL-318", "idem-permission-mismatch",
                    new RiskClusterStatusRequest("flagged", "attempt action without flag permission", "spoofed-operator", 0L));
            assertThat(mismatched.getCode()).isEqualTo(403);
            assertThat(riskRepository.multiAccountClusters.get("CL-318")).isEqualTo("detected");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void k1ClusterFreezeUpdatesC2AuthoritativeAccountStatus() {
        riskRepository.multiAccountClusters.put("CL-318", "flagged");
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-freezer", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k1_cluster_freeze"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_operation_approve")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);

        ApiResult<Map<String, Object>> result = service.updateMultiAccountClusterStatus(
                "CL-318", "idem-k1-c2-link",
                new RiskClusterStatusRequest("frozen", "confirmed linked account cluster", "spoofed", 0L));

        assertThat(result.getCode()).isZero();
        verify(userAccountControlFacade).freezeActiveUsersByUserNos(
                List.of("U00000001", "U00000002"),
                "confirmed linked account cluster", "risk-freezer", "CL-318");
    }

    @Test
    void k1ClusterReleaseRestoresOnlyAccountsFrozenByThatCluster() {
        riskRepository.multiAccountClusters.put("CL-318", "frozen");
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-releaser", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k1_cluster_release"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_operation_approve")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);

        ApiResult<Map<String, Object>> result = service.updateMultiAccountClusterStatus(
                "CL-318", "idem-k1-c2-restore",
                new RiskClusterStatusRequest("released", "cluster false positive cleared", "spoofed", 0L));

        assertThat(result.getCode()).isZero();
        verify(userAccountControlFacade).restoreUsersFrozenBySource(
                List.of("U00000001", "U00000002"),
                "cluster false positive cleared", "risk-releaser", "CL-318");
    }

    @Test
    void multiAccountWriteUsesAuthenticatedOperatorAndRealIdempotencyService() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "authenticated-admin", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k1_write")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<Map<String, Object>> result = service.updateMultiAccountParam(
                    "maxSignupPerIp24h", "idem-authenticated-operator",
                    new RiskParamUpdateRequest("3", "change registration ip threshold", "spoofed-operator"));
            assertThat(result.getCode()).isZero();
            ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
            verify(auditLogService).recordRequired(captor.capture());
            assertThat(captor.getValue().getActorUsername()).isEqualTo("authenticated-admin");
            verify(idempotencyService).execute(
                    org.mockito.ArgumentMatchers.eq("K1_PARAM:maxSignupPerIp24h"),
                    org.mockito.ArgumentMatchers.eq("idem-authenticated-operator"),
                    anyString(), org.mockito.ArgumentMatchers.eq(ApiResult.class), any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void multiAccountReviewNoteDoesNotAbuseSameStateTransition() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-reviewer", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k1_write")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<Map<String, Object>> result = service.updateMultiAccountClusterReviewNote(
                    "CL-318", "idem-review-note",
                    new ffdd.opsconsole.risk.dto.RiskClusterReviewRequest("confirmed shared office false positive", "spoofed", 0L));
            assertThat(result.getCode()).isZero();
            assertThat(riskRepository.multiAccountClusters.get("CL-318")).isEqualTo("detected");
            verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                    "K1_CLUSTER_REVIEW_NOTED".equals(audit.getAction())
                            && "risk-reviewer".equals(audit.getActorUsername())));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void multiAccountWhitelistRejectsInvalidCidrAndPastExpiry() {
        var invalidCidr = service.upsertIpWhitelist(
                "idem-invalid-cidr",
                new ffdd.opsconsole.risk.dto.RiskIpWhitelistRequest(
                        "999.1.1.1/24", "shared office", "invalid cidr must be rejected", "superadmin", "2099-12-31"));
        assertThat(invalidCidr.getCode()).isEqualTo(422);

        var expired = service.upsertIpWhitelist(
                "idem-expired-cidr",
                new ffdd.opsconsole.risk.dto.RiskIpWhitelistRequest(
                        "198.51.100.0/24", "shared office", "past expiry must be rejected", "superadmin", "2020-01-01"));
        assertThat(expired.getCode()).isEqualTo(422);
    }

    @Test
    void kycManualTicketCreatesBackendTicketAndAudits() {
        authenticateK5("risk_k5_ticket_manual");
        ApiResult<Map<String, Object>> result = service.createManualKycReviewTicket(
                "idem-k5",
                new RiskKycManualReviewRequest("usr_55B1", "manual escalation from risk ops", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        Map<?, ?> manualResult = (Map<?, ?>) result.getData().get("manualResult");
        assertThat(manualResult.get("userNo")).isEqualTo("usr_55B1");
        assertThat(manualResult.get("merged")).isEqualTo(false);
        assertThat(String.valueOf(manualResult.get("ticketId")))
                .startsWith("KR-M-");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K5_KYC_REVIEW_MANUAL_CREATED");
    }

    @Test
    void kycReviewOverviewReturnsPagedTriggerQueue() {
        riskRepository.createManualKycReviewTicket("KR-1", "usr_1", "seed review ticket", "system");
        riskRepository.createManualKycReviewTicket("KR-2", "usr_2", "seed review ticket", "system");
        riskRepository.createManualKycReviewTicket("KR-3", "usr_3", "seed review ticket", "system");

        ApiResult<Map<String, Object>> result = service.kycReviewOverview(
                new RiskKycReviewOverviewQueryRequest(2, 2, "all"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("tickets")).isInstanceOf(PageResult.class);
        PageResult<?> tickets = (PageResult<?>) result.getData().get("tickets");
        assertThat(tickets.getTotal()).isEqualTo(3);
        assertThat(tickets.getPageNum()).isEqualTo(2);
        assertThat(tickets.getPageSize()).isEqualTo(2);
        assertThat(tickets.getRecords()).hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"手动触发", "风险分触发"})
    void kycReviewOverviewFiltersEveryProducedTicketType(String filter) {
        riskRepository.createManualKycReviewTicket("KR-MANUAL-FILTER", "usr_1", "manual filter seed", "system");
        riskRepository.createScoreTriggeredKycReviewTicket(
                "KR-SCORE-FILTER", "usr_2", 90, 85, "score filter seed", "system");

        ApiResult<Map<String, Object>> result = service.kycReviewOverview(
                new RiskKycReviewOverviewQueryRequest(1, 10, filter));

        PageResult<?> tickets = (PageResult<?>) result.getData().get("tickets");
        assertThat(tickets.getRecords()).hasSize(1);
        assertThat(((Map<?, ?>) tickets.getRecords().get(0)).get("type")).isEqualTo(filter);
    }

    @Test
    void kycReviewOverviewPreservesFrontendHistoryToneContract() {
        riskRepository.createManualKycReviewTicket("KR-HISTORY", "usr_1", "history contract seed", "system");
        String history = "[[\"2026-07-17 10:00:00\",\"复审驳回·操作人:risk-admin\",\"bad\"]]";
        riskRepository.kycTicketHistJson.put("KR-HISTORY", history);

        ApiResult<Map<String, Object>> result = service.kycReviewOverview(
                new RiskKycReviewOverviewQueryRequest(1, 10, "手动触发"));

        PageResult<?> tickets = (PageResult<?>) result.getData().get("tickets");
        assertThat(((Map<?, ?>) tickets.getRecords().get(0)).get("histJson")).isEqualTo(history);
    }

    @Test
    void kycReviewParamRejectsFreeTextTriggerLine() {
        authenticateK5("risk_k5_write");
        ApiResult<Map<String, Object>> result = service.updateKycReviewParam(
                "reviewTriggerScore",
                "idem-k5-param",
                new RiskKycReviewParamUpdateRequest("高风险就复审", 0L, "reject free text trigger line", "forged-user"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("K5_PARAM_VALUE_INVALID");
    }

    @Test
    void loweringK5ReviewScoreSynchronizesCurrentK4UsersInChunksImmediatelyAndIdempotently() {
        authenticateK5("risk_k5_write");
        riskRepository.kycReviewParams.put("reviewTriggerScore", ">= 95");
        riskRepository.transitionK4KycReviewTriggerState("usr_55B1", 91, 95, "baseline-95");

        ApiResult<Map<String, Object>> lowered = service.updateKycReviewParam(
                "reviewTriggerScore",
                "idem-k5-threshold-lower",
                new RiskKycReviewParamUpdateRequest(">= 85", 0L,
                        "lower review line for current high scores", "ignored"));

        assertThat(lowered.getCode()).isZero();
        assertThat(lowered.getData()).containsEntry("scoreTriggeredTickets", 1);
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_TRIGGERED_BY_SCORE".equals(audit.getAction())
                        && audit.getDetail().toString().contains(
                        K4KycReviewTriggerService.SOURCE_REVIEW_THRESHOLD_CHANGE)));

        ApiResult<Map<String, Object>> repeated = service.updateKycReviewParam(
                "reviewTriggerScore",
                "idem-k5-threshold-repeat",
                new RiskKycReviewParamUpdateRequest(">= 85", 1L,
                        "repeat same review line without duplicate trigger", "ignored"));

        assertThat(repeated.getCode()).isZero();
        assertThat(repeated.getData()).containsEntry("scoreTriggeredTickets", 0);
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
    }

    @Test
    void kycReviewDecisionUpdatesBackendTicketAndAudits() {
        authenticateK5("risk_k5_ticket_reject");
        riskRepository.createManualKycReviewTicket("KR-1", "usr_55B1", "seed review ticket", "system");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                "KR-1",
                "idem-k5",
                new RiskKycReviewDecisionRequest("rejected", 0L, "KYC_MATERIAL_INVALID", "failed manual kyc review", "forged-user"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketStatus("KR-1")).isEqualTo("rejected");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K5_KYC_REVIEW_DECIDED");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("superadmin");
    }

    @Test
    void k5DecisionReplayReturnsCachedSuccessWithoutSecondWriteOrAudit() {
        authenticateK5("risk_k5_ticket_reject");
        riskRepository.createManualKycReviewTicket("KR-IDEMP", "usr_55B1", "seed review ticket", "system");
        AtomicReference<String> cachedHash = new AtomicReference<>();
        AtomicReference<Object> cachedResult = new AtomicReference<>();
        doAnswer(invocation -> {
            String hash = invocation.getArgument(2);
            if (cachedResult.get() != null) {
                if (!hash.equals(cachedHash.get())) {
                    throw new ffdd.opsconsole.shared.exception.BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                }
                return cachedResult.get();
            }
            Object result = ((java.util.function.Supplier<?>) invocation.getArgument(4)).get();
            cachedHash.set(hash);
            cachedResult.set(result);
            return result;
        }).when(idempotencyService).execute(anyString(), anyString(), anyString(), any(), any());
        RiskKycReviewDecisionRequest request = new RiskKycReviewDecisionRequest(
                "rejected", 0L, "KYC_MATERIAL_INVALID", "reject invalid identity evidence", "ignored");

        ApiResult<Map<String, Object>> first = service.decideKycReviewTicket("KR-IDEMP", "idem-k5-replay", request);
        ApiResult<Map<String, Object>> replay = service.decideKycReviewTicket("KR-IDEMP", "idem-k5-replay", request);

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(riskRepository.kycDecisionWrites).isEqualTo(1);
        verify(auditLogService, times(1)).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_DECIDED".equals(audit.getAction())));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.decideKycReviewTicket(
                "KR-IDEMP", "idem-k5-replay",
                new RiskKycReviewDecisionRequest("rejected", 0L, "KYC_MATERIAL_INVALID",
                        "different payload must conflict", "ignored")))
                .isInstanceOfSatisfying(ffdd.opsconsole.shared.exception.BizException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(409))
                .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        assertThat(riskRepository.kycDecisionWrites).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "KYC_MATERIAL_INVALID", "IDENTITY_MISMATCH", "SANCTIONS_LIST_MATCH", "OTHER"})
    void k5AcceptsEveryCanonicalRejectReasonCode(String reasonCode) {
        authenticateK5("risk_k5_ticket_reject");
        String ticketId = "KR-CODE-" + reasonCode;
        riskRepository.createManualKycReviewTicket(ticketId, "usr_55B1", "seed review ticket", "system");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                ticketId, "idem-" + reasonCode,
                new RiskKycReviewDecisionRequest("rejected", 0L, reasonCode,
                        "reject using canonical reason code", "ignored"));

        assertThat(result.getCode()).isZero();
    }

    @Test
    void kycReviewDecisionUpdatesC4AndReleasesD2Source() {
        authenticateK5("risk_k5_ticket_pass");
        riskRepository.createLargeWithdrawalKycReviewTicket(
                "KR-D2-1",
                "usr_55B1",
                new BigDecimal("8200.00"),
                "WD-90412",
                "APPROVED",
                "large withdrawal source",
                "system");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                "KR-D2-1",
                "idem-k5",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED", "passed enhanced kyc review", "forged-user"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketStatus("KR-D2-1")).isEqualTo("passed");
        assertThat(userKycStatusFacade.lastUserNo).isEqualTo("usr_55B1");
        assertThat(userKycStatusFacade.lastKycStatus).isEqualTo("APPROVED");
        assertThat(financeWithdrawalKycReviewFacade.releasedWithdrawalNo).isEqualTo("WD-90412");
        assertThat(financeWithdrawalKycReviewFacade.rejectedWithdrawalNo).isNull();
        assertThat(marketExchangeKycReviewFacade.releasedExchangeNo).isNull();
    }

    @Test
    void kycReviewDecisionReleasesEveryD2SourceMergedIntoManualTicket() {
        authenticateK5("risk_k5_ticket_pass");
        riskRepository.createManualKycReviewTicket(
                "KR-D2-MULTI", "usr_55B1", "manual review before withdrawals", "system");
        riskRepository.linkKycReviewSource("KR-D2-MULTI", "D2", "WD-FIRST");
        riskRepository.linkKycReviewSource("KR-D2-MULTI", "D2", "WD-SECOND");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                "KR-D2-MULTI", "idem-k5-multi-d2",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED",
                        "approve every linked withdrawal", "forged-user"));

        assertThat(result.getCode()).isZero();
        assertThat(financeWithdrawalKycReviewFacade.releasedWithdrawalNos)
                .containsExactly("WD-FIRST", "WD-SECOND");
    }

    @Test
    void kycReviewDecisionRejectsEveryD2SourceMergedIntoScoreTicket() {
        authenticateK5("risk_k5_ticket_reject");
        riskRepository.createScoreTriggeredKycReviewTicket(
                "KR-D2-REJECT-MULTI", "usr_55B1", 91, 85, "score review before withdrawals", "system");
        riskRepository.linkKycReviewSource("KR-D2-REJECT-MULTI", "D2", "WD-REJECT-1");
        riskRepository.linkKycReviewSource("KR-D2-REJECT-MULTI", "D2", "WD-REJECT-2");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                "KR-D2-REJECT-MULTI", "idem-k5-multi-d2-reject",
                new RiskKycReviewDecisionRequest("rejected", 0L, "KYC_MATERIAL_INVALID",
                        "reject every linked withdrawal", "forged-user"));

        assertThat(result.getCode()).isZero();
        assertThat(financeWithdrawalKycReviewFacade.rejectedWithdrawalNos)
                .containsExactly("WD-REJECT-1", "WD-REJECT-2");
    }

    @Test
    void kycReviewDecisionRequiresMatchingPermissionAndCurrentVersion() {
        authenticateK5("risk_k5_ticket_reject");
        riskRepository.createManualKycReviewTicket("KR-2", "usr_55B1", "seed review ticket", "system");

        ApiResult<Map<String, Object>> forbidden = service.decideKycReviewTicket(
                "KR-2", "idem-k5-pass-forbidden",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED", "passed enhanced review", "ignored"));
        assertThat(forbidden.getCode()).isEqualTo(403);

        ApiResult<Map<String, Object>> conflict = service.decideKycReviewTicket(
                "KR-2", "idem-k5-version-conflict",
                new RiskKycReviewDecisionRequest("rejected", 7L, "KYC_MATERIAL_INVALID", "failed enhanced review", "ignored"));
        assertThat(conflict.getCode()).isEqualTo(409);
        assertThat(riskRepository.kycTicketStatus("KR-2")).isEqualTo("in-review");
        verify(auditLogService, times(2)).recordRequiredInNewTransaction(
                org.mockito.ArgumentMatchers.argThat(audit ->
                        "K5_KYC_REVIEW_DECISION_REJECTED".equals(audit.getAction())
                                && "REJECTED".equals(audit.getResult())
                                && audit.getDetail() instanceof Map<?, ?> detail
                                && Boolean.FALSE.equals(detail.get("businessDataChanged"))));
    }

    @Test
    void k5DecisionRejectsUnavailableC4UserBeforeAnyBusinessSideEffectAndKeepsIdempotencyRecord() {
        authenticateK5("risk_k5_ticket_pass");
        riskRepository.createLargeWithdrawalKycReviewTicket(
                "KR-ORPHAN", "missing-user", new BigDecimal("1200"), "WD-ORPHAN",
                "PENDING", "orphaned account regression", "system");
        userKycStatusFacade.existingUsers.remove("missing-user");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                "KR-ORPHAN", "idem-k5-orphan",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED",
                        "must fail closed when C4 user no longer exists", "ignored"));

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("K5_REVIEW_USER_NOT_FOUND");
        assertThat(riskRepository.kycTicketStatus("KR-ORPHAN")).isEqualTo("in-review");
        assertThat(riskRepository.kycDecisionWrites).isZero();
        assertThat(userKycStatusFacade.lastUserNo).isNull();
        assertThat(financeWithdrawalKycReviewFacade.releasedWithdrawalNo).isNull();
        assertThat(financeWithdrawalKycReviewFacade.rejectedWithdrawalNo).isNull();
        assertThat(marketExchangeKycReviewFacade.releasedExchangeNo).isNull();
        assertThat(marketExchangeKycReviewFacade.rejectedExchangeNo).isNull();
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K5_DECISION:KR-ORPHAN"),
                org.mockito.ArgumentMatchers.eq("idem-k5-orphan"),
                anyString(), org.mockito.ArgumentMatchers.eq(ApiResult.class), any());
        verify(auditLogService).recordRequiredInNewTransaction(
                org.mockito.ArgumentMatchers.argThat(audit ->
                        "K5_KYC_REVIEW_DECISION_REJECTED".equals(audit.getAction())
                                && "K5_REVIEW_USER_NOT_FOUND".equals(
                                ((Map<?, ?>) audit.getDetail()).get("reasonCode"))
                                && Boolean.FALSE.equals(
                                ((Map<?, ?>) audit.getDetail()).get("businessDataChanged"))));
    }

    @Test
    void k5DecisionAuditsMissingLockedAndTerminalRejections() {
        authenticateK5("risk_k5_ticket_pass");
        var missing = service.decideKycReviewTicket("KR-MISSING", "idem-k5-missing-decision",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED", "approve after complete review", "ignored"));

        riskRepository.createManualKycReviewTicket("KR-LOCKED", "usr_55B1", "seed review ticket", "system");
        when(lockMapper.countActiveByTarget("K", "ticket", "KR-LOCKED")).thenReturn(1);
        var locked = service.decideKycReviewTicket("KR-LOCKED", "idem-k5-locked",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED", "approve after complete review", "ignored"));
        when(lockMapper.countActiveByTarget("K", "ticket", "KR-LOCKED")).thenReturn(0);

        riskRepository.kycTickets.put("KR-LOCKED", "passed");
        var terminal = service.decideKycReviewTicket("KR-LOCKED", "idem-k5-terminal",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED", "approve after complete review", "ignored"));

        assertThat(List.of(missing.getMessage(), locked.getMessage(), terminal.getMessage()))
                .containsExactly("K5_REVIEW_TICKET_NOT_FOUND", "OBJECT_LOCKED_BY_A2", "K5_REVIEW_TICKET_NOT_REVIEWABLE");
        verify(auditLogService, times(3)).recordRequiredInNewTransaction(
                org.mockito.ArgumentMatchers.argThat(audit ->
                        "K5_KYC_REVIEW_DECISION_REJECTED".equals(audit.getAction())));
    }

    @Test
    void k5DownstreamFailureWritesIndependentFailureAudit() {
        authenticateK5("risk_k5_ticket_pass");
        riskRepository.createLargeWithdrawalKycReviewTicket(
                "KR-D2-FAIL", "usr_55B1", new BigDecimal("8200"), "WD-FAIL",
                "PENDING", "seed downstream failure", "system");
        financeWithdrawalKycReviewFacade.succeeds = false;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.decideKycReviewTicket(
                "KR-D2-FAIL", "idem-k5-downstream-fail",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED",
                        "approve after complete review", "ignored")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K5_SOURCE_STATE_UPDATE_FAILED");
        verify(auditLogService).recordRequiredInNewTransaction(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_DECISION_FAILED".equals(audit.getAction())
                        && "FAILED".equals(audit.getResult())
                        && audit.getDetail() instanceof Map<?, ?> detail
                        && Boolean.FALSE.equals(detail.get("businessDataChanged"))));
    }

    @Test
    void k5C4FailureWritesIndependentFailureAudit() {
        authenticateK5("risk_k5_ticket_pass");
        riskRepository.createManualKycReviewTicket("KR-C4-FAIL", "usr_55B1", "seed review ticket", "system");
        userKycStatusFacade.succeeds = false;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.decideKycReviewTicket(
                "KR-C4-FAIL", "idem-k5-c4-fail",
                new RiskKycReviewDecisionRequest("passed", 0L, "KYC_PASSED",
                        "approve after complete review", "ignored")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K5_C4_KYC_UPDATE_FAILED");
        verify(auditLogService).recordRequiredInNewTransaction(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_DECISION_FAILED".equals(audit.getAction())
                        && "FAILED".equals(audit.getResult())));
    }

    @Test
    void kycRejectionRequiresKnownReasonCode() {
        authenticateK5("risk_k5_ticket_reject");
        riskRepository.createManualKycReviewTicket("KR-REJECT", "usr_55B1", "seed review ticket", "system");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                "KR-REJECT", "idem-k5-reject-code",
                new RiskKycReviewDecisionRequest("rejected", 0L, "FREE_TEXT", "failed enhanced review", "ignored"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(riskRepository.kycTicketStatus("KR-REJECT")).isEqualTo("in-review");
    }

    @Test
    void kycManualTicketRejectsMissingUserAndMergesExistingOpenTicket() {
        authenticateK5("risk_k5_ticket_manual");
        userKycStatusFacade.existingUsers.remove("missing-user");
        ApiResult<Map<String, Object>> missing = service.createManualKycReviewTicket(
                "idem-k5-missing", new RiskKycManualReviewRequest("missing-user", "manual escalation request", "ignored"));
        assertThat(missing.getCode()).isEqualTo(404);

        riskRepository.createManualKycReviewTicket("KR-OPEN", "usr_55B1", "seed review ticket", "system");
        ApiResult<Map<String, Object>> merged = service.createManualKycReviewTicket(
                "idem-k5-merge", new RiskKycManualReviewRequest("usr_55B1", "merge repeated signal", "ignored"));
        assertThat(merged.getCode()).isZero();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        assertThat(riskRepository.kycTicketVersions.get("KR-OPEN")).isEqualTo(1L);
        Map<?, ?> mergedResult = (Map<?, ?>) merged.getData().get("manualResult");
        assertThat(mergedResult.get("ticketId")).isEqualTo("KR-OPEN");
        assertThat(mergedResult.get("userNo")).isEqualTo("usr_55B1");
        assertThat(mergedResult.get("merged")).isEqualTo(true);
    }

    @Test
    void concurrentManualTriggerRecoversByMergingWinningOpenTicket() {
        authenticateK5("risk_k5_ticket_manual");
        riskRepository.simulateManualInsertRace = true;

        ApiResult<Map<String, Object>> result = service.createManualKycReviewTicket(
                "idem-k5-race", new RiskKycManualReviewRequest("usr_55B1", "concurrent manual escalation", "ignored"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        assertThat(riskRepository.kycTicketVersions.get("KR-RACE")).isEqualTo(1L);
        Map<?, ?> manualResult = (Map<?, ?>) result.getData().get("manualResult");
        assertThat(manualResult.get("ticketId")).isEqualTo("KR-RACE");
        assertThat(manualResult.get("merged")).isEqualTo(true);
    }

    @Test
    void concurrentManualTriggerLocksExistingOpenTicketBeforeMerging() {
        authenticateK5("risk_k5_ticket_manual");
        riskRepository.createManualKycReviewTicket("KR-OPEN-LOCK", "usr_55B1", "seed review ticket", "system");
        riskRepository.simulateUnlockedManualMergeRace = true;

        ApiResult<Map<String, Object>> result = service.createManualKycReviewTicket(
                "idem-k5-open-race",
                new RiskKycManualReviewRequest("usr_55B1", "concurrent existing-ticket signal", "ignored"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        assertThat(riskRepository.kycTicketVersions.get("KR-OPEN-LOCK")).isEqualTo(1L);
    }

    @Test
    void repeatedManualMergesRemainVisibleAsAccumulatedOverviewReasons() {
        authenticateK5("risk_k5_ticket_manual");
        riskRepository.createManualKycReviewTicket("KR-MERGE-INFO", "usr_55B1", "initial trigger reason", "system");

        service.createManualKycReviewTicket(
                "idem-k5-merge-info-1",
                new RiskKycManualReviewRequest("usr_55B1", "first repeated signal", "ignored"));
        ApiResult<Map<String, Object>> second = service.createManualKycReviewTicket(
                "idem-k5-merge-info-2",
                new RiskKycManualReviewRequest("usr_55B1", "second repeated signal", "ignored"));

        PageResult<?> tickets = (PageResult<?>) second.getData().get("tickets");
        String infoJson = String.valueOf(((Map<?, ?>) tickets.getRecords().get(0)).get("infoJson"));
        assertThat(infoJson).contains("first repeated signal", "second repeated signal");
        assertThat(riskRepository.kycTicketVersions.get("KR-MERGE-INFO")).isEqualTo(2L);
    }

    @Test
    void kycParamAndAlertSubscriptionUseOptimisticVersioning() {
        authenticateK5("risk_k5_write");
        ApiResult<Map<String, Object>> updated = service.updateKycReviewParam(
                "reviewSlaDays", "idem-k5-param-ok",
                new RiskKycReviewParamUpdateRequest("5", 0L, "tighten review service level", "ignored"));
        assertThat(updated.getCode()).isZero();

        ApiResult<Map<String, Object>> stale = service.updateKycReviewParam(
                "reviewSlaDays", "idem-k5-param-stale",
                new RiskKycReviewParamUpdateRequest("4", 0L, "stale review service level", "ignored"));
        assertThat(stale.getCode()).isEqualTo(409);

        ApiResult<Map<String, Object>> subscription = service.updateKycAlertSubscription(
                "idem-k5-alerts",
                new RiskKycAlertSubscriptionRequest(List.of("sla-breach"), List.of("in-app"), 0L,
                        "subscribe to material KYC events", "ignored"));
        assertThat(subscription.getCode()).isZero();
        assertThat(((Map<?, ?>) subscription.getData().get("subscription")).get("version")).isEqualTo(1L);
    }

    @Test
    void changingK5SlaAuditsTheNumberOfRecomputedOpenTickets() {
        authenticateK5("risk_k5_write");
        riskRepository.createManualKycReviewTicket("KR-SLA", "usr_55B1", "seed review ticket", "system");

        ApiResult<Map<String, Object>> result = service.updateKycReviewParam(
                "reviewSlaDays", "idem-k5-sla-recompute",
                new RiskKycReviewParamUpdateRequest("5", 0L, "apply new SLA to open reviews", "ignored"));

        assertThat(result.getCode()).isZero();
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "K5_KYC_REVIEW_PARAM_CHANGED".equals(audit.getAction())
                        && audit.getDetail() instanceof Map<?, ?> detail
                        && Integer.valueOf(1).equals(detail.get("slaRecomputedTickets"))));
    }

    @Test
    void k5OverviewOnlyReturnsSubscribedProducedAlertTypes() {
        authenticateK5("risk_k5_write");
        riskRepository.kycAlertRows.add(Map.of("eventKey", "threshold-hit:KR-1", "title", "threshold"));
        riskRepository.kycAlertRows.add(Map.of("eventKey", "sla-breach:KR-2", "title", "sla"));
        service.updateKycAlertSubscription("idem-k5-alert-filter",
                new RiskKycAlertSubscriptionRequest(List.of("sla-breach"), List.of("in-app"), 0L,
                        "only subscribe to SLA breaches", "ignored"));

        ApiResult<Map<String, Object>> overview = service.kycReviewOverview();

        List<?> alerts = (List<?>) overview.getData().get("alerts");
        assertThat(alerts).hasSize(1);
        assertThat(((Map<?, ?>) alerts.get(0)).get("title")).isEqualTo("sla");
    }

    @Test
    void k5SubscriptionAcceptsBurstAlertsButStillRejectsUnimplementedChannels() {
        authenticateK5("risk_k5_write");
        var email = service.updateKycAlertSubscription("idem-k5-email",
                new RiskKycAlertSubscriptionRequest(List.of("sla-breach"), List.of("email"), 0L,
                        "email delivery is unavailable", "ignored"));
        var burst = service.updateKycAlertSubscription("idem-k5-burst",
                new RiskKycAlertSubscriptionRequest(List.of("large-withdraw-burst"), List.of("in-app"), 0L,
                        "subscribe to withdrawal burst detector", "ignored"));
        assertThat(email.getCode()).isEqualTo(422);
        assertThat(burst.getCode()).isZero();
        assertThat(((Map<?, ?>) burst.getData().get("subscription")).get("alertTypes"))
                .isEqualTo(List.of("large-withdraw-burst"));
    }

    @Test
    void k5WritesRequireReasonBetweenEightAndTwoHundredCharacters() {
        authenticateK5("risk_k5_write");
        var shortReason = service.updateKycReviewParam("reviewSlaDays", "idem-k5-short",
                new RiskKycReviewParamUpdateRequest("5", 0L, "1234567", "ignored"));
        var longReason = service.updateKycReviewParam("reviewSlaDays", "idem-k5-long",
                new RiskKycReviewParamUpdateRequest("5", 0L, "x".repeat(201), "ignored"));

        assertThat(shortReason.getCode()).isEqualTo(422);
        assertThat(longReason.getCode()).isEqualTo(422);
        assertThat(riskRepository.kycParamVersions.get("reviewSlaDays")).isZero();
    }

    @Test
    void k4ConcurrentTriggerMergesWinningOpenTicket() {
        riskRepository.simulateScoreInsertRace = true;

        ApiResult<RiskScoreUserView> result = service.overrideScore("usr_55B1", "idem-k4-k5-race",
                new RiskScoreOverrideRequest(90, 0L, "concurrent escalation into KYC", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        assertThat(riskRepository.kycTicketVersions.get("KR-K4-RACE")).isEqualTo(1L);
    }

    private void authenticateK5(String authority) {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "superadmin", "n/a", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(authority)));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void replayK1ClusterFreezeInvokesUpdateClusterStatusAndSucceeds() {
        riskRepository.multiAccountClusters.put("CL-318", "flagged");
        AuditReplayCommand cmd = new AuditReplayCommand("K", "k1_cluster_freeze", Map.of(
                "clusterId", "CL-318", "expectedVersion", 0));
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay freeze cluster", "idem-replay-k1-freeze");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.multiAccountClusters.get("CL-318")).isEqualTo("frozen");
    }

    @Test
    void delegatedProposerCannotBypassA2WithDirectK1ServiceCallButApprovedReplayStillWorks() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-user", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_proposal_create"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k1_cluster_freeze")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var denied = service.updateMultiAccountClusterStatus(
                    "CL-318", "idem-direct-k1-bypass",
                    new RiskClusterStatusRequest("frozen", "attempt direct delegated freeze", "risk-user", 0L));

            assertThat(denied.getCode()).isEqualTo(403);
            assertThat(denied.getMessage()).isEqualTo("A2_PROPOSAL_REQUIRED");
            assertThat(riskRepository.multiAccountClusters.get("CL-318")).isNotEqualTo("frozen");
            verify(auditLogService).recordRequiredInNewTransaction(org.mockito.ArgumentMatchers.argThat(audit ->
                    "A2_DIRECT_EXECUTION_REJECTED".equals(audit.getAction())
                            && "REJECTED".equals(audit.getResult())
                            && audit.getDetail() instanceof Map<?, ?> detail
                            && Boolean.FALSE.equals(detail.get("businessDataChanged"))));

            ffdd.opsconsole.platform.application.A2ReplayContext.enterReplay();
            try {
                // The approved command still obeys the K1 state machine: flag before freeze.
                riskRepository.multiAccountClusters.put("CL-318", "flagged");
                var replayed = service.replay(
                        new AuditReplayCommand("K", "k1_cluster_freeze", Map.of("clusterId", "CL-318", "expectedVersion", 0)),
                        new AuditReplayContext("approver", "approved replay freeze", "idem-approved-k1-replay"));
                assertThat(replayed.getCode()).isZero();
                assertThat(riskRepository.multiAccountClusters.get("CL-318")).isEqualTo("frozen");
            } finally {
                ffdd.opsconsole.platform.application.A2ReplayContext.exitReplay();
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void delegatedProposerCannotBypassK2FreezeAndApprovedReplayUsesBothVersions() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "risk-k2-user", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a2_proposal_create"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k2_row_freeze")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            riskRepository.multiAccountClusters.put("CL-318", "flagged");
            var denied = service.executeArbitrageAction(
                    "T-318", "freeze-cluster", "idem-direct-k2-bypass",
                    new RiskArbitrageActionRequest("attempt direct K2 linked freeze", "spoofed", 0L, 0L));
            assertThat(denied.getCode()).isEqualTo(403);
            assertThat(denied.getMessage()).isEqualTo("A2_PROPOSAL_REQUIRED");

            ffdd.opsconsole.platform.application.A2ReplayContext.enterReplay();
            try {
                var replayed = service.replay(
                        new AuditReplayCommand("K", "k2_row_freeze", Map.of(
                                "rowId", "T-318", "expectedVersion", 0, "clusterExpectedVersion", 0)),
                        new AuditReplayContext("approver", "approved K2 linked freeze", "idem-approved-k2-replay"));
                assertThat(replayed.getCode()).isZero();
                assertThat(riskRepository.multiAccountClusters.get("CL-318")).isEqualTo("frozen");
                assertThat(riskRepository.findArbitrageRow("T-318").orElseThrow().version()).isEqualTo(1L);
            } finally {
                ffdd.opsconsole.platform.application.A2ReplayContext.exitReplay();
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void replayK5TicketPassInvokesDecideTicketAndSucceeds() {
        riskRepository.createManualKycReviewTicket("KR-1", "usr_55B1", "seed review ticket", "system");
        AuditReplayCommand cmd = new AuditReplayCommand("K", "k5_ticket_pass", Map.of(
                "ticketId", "KR-1"));
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay pass ticket", "idem-replay-k5-pass");

        ffdd.opsconsole.platform.application.A2ReplayContext.enterReplay();
        try {
            ApiResult<?> result = service.replay(cmd, ctx);
            assertThat(result.getCode()).isZero();
            assertThat(riskRepository.kycTicketStatus("KR-1")).isEqualTo("passed");
        } finally {
            ffdd.opsconsole.platform.application.A2ReplayContext.exitReplay();
        }
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        AuditReplayCommand cmd = new AuditReplayCommand("K", "k_unknown_op", Map.of());
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay unknown op", "idem-replay-unknown");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:k_unknown_op");
    }

    private void authenticateK3() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "authenticated-risk-lead", "n/a", List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k3_write"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k3_rule_create"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k3_rule_toggle"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("risk_k3_rule_archive")));
        authentication.setDetails(Map.of("username", "authenticated-risk-lead"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static final class FakeRiskOpsRepository implements RiskOpsRepository {
        private int e3TradeinProjectionRefreshes;
        private RiskCaseView caseView = new RiskCaseView(
                "RD-1", 1L, "WITHDRAWAL", "W-1", "US", "L1", "REVIEW", "manual review", 88, "K4", "REVIEWING", null,
                null, LocalDateTime.now().minusHours(1));
        private final List<RiskRuleView> rules = new ArrayList<>(List.of(
                new RiskRuleView("WR-01", "金额", "单笔 >= $1,000", "manual", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-02", "速度", "24h > 3 笔 或 > $5,000", "delay", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-03", "新账户", "注册 < 7 天", "delay", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-04", "地址信誉", "黑名单 / 低信誉地址", "freeze", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-DRAFT", "金额", "单笔 >= $2,000", "manual", "draft", false, 10, 0L,
                        LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-06", "金额", "单笔 >= $500(P1 期旧线)", "manual", "archived", true, LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(20))));
        private final List<RiskRuleHitView> withdrawHits = new ArrayList<>(List.of(
                new RiskRuleHitView("WD-1", "usr_1", "$1,200", "WR-01", "金额", "manual", "单笔大额提现转人工", "今天 10:00"),
                new RiskRuleHitView("WD-2", "usr_2", "$400", "WR-02", "速度", "delay", "24h 提现速度过线延迟", "今天 10:05"),
                new RiskRuleHitView("WD-3", "usr_3", "$500", "WR-02", "速度", "delay", "24h 提现速度过线延迟", "今天 10:10")));
        private final List<RiskWithdrawCandidateView> withdrawCandidates = new ArrayList<>(List.of(
                new RiskWithdrawCandidateView("WD-K3-1", "U00000001", BigDecimal.valueOf(1_500), 1,
                        BigDecimal.valueOf(1_500), 100, "normal", "", "USDT-TRC20", "TR-DRY-ONE"),
                new RiskWithdrawCandidateView("WD-K3-2", "U00000002", BigDecimal.valueOf(80), 5,
                        BigDecimal.valueOf(6_000), 3, "low", "", "USDT-TRC20", "TR-DRY-TWO")));
        private final List<RiskArbitrageParamView> arbitrageParams = new ArrayList<>(List.of(
                new RiskArbitrageParamView("trialCycleThreshold", "试用循环异常线", ">= 3 次 / 30 天", "sub", "note"),
                new RiskArbitrageParamView("welcomeGiftAnomalyThreshold", "新人礼异常发放线", ">= 2 笔 / 实体", "sub", "note"),
                new RiskArbitrageParamView("leaderboardVelocityMultiplier", "刷榜增速异常倍数", "> 5x 基线", "sub", "note"),
                new RiskArbitrageParamView("otpGate.resendSeconds", "重发冷却", "60", "sub", "note"),
                new RiskArbitrageParamView("otpGate.captchaAfterSends", "图形验证触发次数", "3", "sub", "note"),
                new RiskArbitrageParamView("otpGate.otpTtlSeconds", "验证码有效期", "300", "sub", "note")));
        private final List<RiskArbitrageRowView> arbitrageRows = new ArrayList<>(List.of(
                new RiskArbitrageRowView("T-318", "trial", "CL-318", List.of("CL-318", "7 次"), 3, List.of("freeze", "flag"), null)));
        private final List<K2Signal> k2Signals = new ArrayList<>();
        private final List<RiskOpsRepository.TrialCycleDetection> trialCycleDetections = new ArrayList<>();
        private final List<RiskScoreDimensionView> scoreDimensions = new ArrayList<>(List.of(
                new RiskScoreDimensionView("multiAccount", "多账户命中", "来自 K1", 25),
                new RiskScoreDimensionView("arbitrage", "套利信号", "来自 K2", 20),
                new RiskScoreDimensionView("kycStatus", "实名状态", "来自 C4", 20),
                new RiskScoreDimensionView("withdrawVelocity", "提现速度", "资金事件", 15),
                new RiskScoreDimensionView("accountAge", "账户年龄", "注册时间", 10),
                new RiskScoreDimensionView("anomalyBehavior", "异常行为", "行为事件", 10)));
        private RiskScoreConfigView scoreConfig = new RiskScoreConfigView("全部启用", 40, 70, 85);
        private RiskScoreUserView scoreUser = new RiskScoreUserView(
                "usr_55B1", 91, 91, false, "高风险", "bad", "k4-v1", 0L,
                "2026-07-16 09:00:00", "2026-07-16 09:00:00",
                List.of(new RiskScoreContributionView(
                        "multiAccount", "多账户命中", true, "簇 CL-318", 100, 25, 25)));
        private RiskScoreModelView activeScoreModel = new RiskScoreModelView(
                1L, 0L, "active",
                Map.of(
                        "multiAccount", 25, "arbitrage", 20, "kycStatus", 20,
                        "withdrawVelocity", 15, "accountAge", 10, "anomalyBehavior", 10),
                Map.of(
                        "multiAccount", true, "arbitrage", true, "kycStatus", true,
                        "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true),
                40, 70, 85, "initial K4 model", "system", "system",
                "2026-07-16 09:00:00", "2026-07-16 09:00:00");
        private RiskScoreModelView draftScoreModel;
        private RiskScoreModelView historicalScoreModel;
        private int archivedScoreModels;
        private final List<RiskScoreOverrideView> scoreOverrides = new ArrayList<>();
        private final Map<String, String> multiAccountParams = new LinkedHashMap<>(Map.of(
                "maxSignupPerIp24h", "3",
                "maxAccountsPerDevice", "2",
                "maxAccountsPerPaymentInstrument", "2",
                "clusterFreezeSuggestThreshold", "0.7",
                "linkWeight", "设备 0.50 · 支付 0.40 · IP 0.10"));
        private final Map<String, String> multiAccountClusters = new LinkedHashMap<>(Map.of("CL-318", "detected"));
        private final Map<String, Long> multiAccountClusterVersions = new LinkedHashMap<>(Map.of("CL-318", 0L));
        private final Map<String, String> multiAccountLayers = new LinkedHashMap<>(Map.of("CL-318", "device", "CL-309", "ip", "CL-296", "ip"));
        private final List<String> ipWhitelistRows = new ArrayList<>(List.of("103.86.44.0/24", "202.120.0.0/16"));
        private final Map<String, RiskOpsRepository.IpWhitelistState> ipWhitelistStates = new LinkedHashMap<>(Map.of(
                "103.86.44.0/24", new RiskOpsRepository.IpWhitelistState(
                        "103.86.44.0/24", "seed whitelist", "seed-operator", "2099-12-31", true),
                "202.120.0.0/16", new RiskOpsRepository.IpWhitelistState(
                        "202.120.0.0/16", "seed whitelist", "seed-operator", "2099-12-31", true)));
        private final Map<String, String> kycTickets = new LinkedHashMap<>();
        private final Map<String, String> kycTicketTypes = new LinkedHashMap<>();
        private final Map<String, String> kycTicketUsers = new LinkedHashMap<>();
        private final Map<String, String> kycTicketInfoJson = new LinkedHashMap<>();
        private final Map<String, String> kycTicketHistJson = new LinkedHashMap<>();
        private final Map<String, List<RiskOpsRepository.KycReviewSource>> kycTicketSources = new LinkedHashMap<>();
        private final Map<String, Long> kycTicketVersions = new LinkedHashMap<>();
        private final Map<String, Boolean> k4KycTriggerAbove = new LinkedHashMap<>();
        private final Map<String, Integer> k4KycTriggerThreshold = new LinkedHashMap<>();
        private final Map<String, Long> kycParamVersions = new LinkedHashMap<>(Map.of(
                "largeWithdrawReviewUsdt", 0L,
                "cumulativeKycThresholdUsdt", 0L,
                "reviewSlaDays", 0L,
                "reviewTriggerScore", 0L));
        private List<String> kycAlertTypes = new ArrayList<>(List.of("sla-breach"));
        private List<String> kycAlertChannels = new ArrayList<>(List.of("in-app"));
        private long kycAlertSubscriptionVersion;
        private final List<Map<String, Object>> kycAlertRows = new ArrayList<>();
        private boolean simulateManualInsertRace;
        private boolean simulateUnlockedManualMergeRace;
        private boolean manualOpenReadLocked;
        private boolean simulateScoreInsertRace;
        private int kycDecisionWrites;
        private final Map<String, String> kycReviewParams = new LinkedHashMap<>(Map.of(
                "largeWithdrawReviewUsdt", ">= $1,000",
                "cumulativeKycThresholdUsdt", "$100",
                "reviewSlaDays", "7",
                "reviewTriggerScore", ">= 85"));
        private RiskCaseQueryRequest lastPageRequest;

        private static <T> PageResult<T> pageOf(List<T> rows, int pageNum, int pageSize) {
            int start = Math.max(0, (pageNum - 1) * pageSize);
            int end = Math.min(rows.size(), start + pageSize);
            List<T> records = start >= rows.size() ? List.of() : rows.subList(start, end);
            return new PageResult<>(rows.size(), pageNum, pageSize, records);
        }

        @Override
        public Map<String, Object> overview() {
            return new LinkedHashMap<>(Map.of("totalCases", 1L, "manualReview", 1L));
        }

        @Override
        public List<RiskCaseView> search(Long userId, String status, String decision, int limit) {
            return List.of(caseView);
        }

        @Override
        public PageResult<RiskCaseView> pageCases(RiskCaseQueryRequest request) {
            lastPageRequest = request;
            int pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            int pageSize = request == null || request.pageSize() == null ? 50 : request.pageSize();
            return new PageResult<>(1, pageNum, pageSize, List.of(caseView));
        }

        @Override
        public Optional<RiskCaseView> findByCaseNo(String caseNo) {
            return caseView.caseNo().equals(caseNo) ? Optional.of(caseView) : Optional.empty();
        }

        @Override
        public void updateDecision(String caseNo, String decision, String reason, String operator) {
            caseView = new RiskCaseView(
                    caseView.caseNo(), caseView.userId(), caseView.bizType(), caseView.bizNo(), caseView.region(), caseView.userLevel(),
                    decision, reason, caseView.riskScore(), caseView.ruleCodes(), "FINALIZED", operator, LocalDateTime.now(), caseView.createdAt());
        }

        @Override
        public void recordSignal(String signalNo, Long userId, String signalType, String severity, String evidence, String operator) {
            k2Signals.add(new K2Signal(signalNo, userId, signalType));
        }

        @Override
        public boolean recordSignalIfAbsent(
                String signalNo, Long userId, String signalType, String severity, String evidence, String operator) {
            if (k2Signals.stream().anyMatch(signal -> signal.signalNo().equals(signalNo))) return false;
            k2Signals.add(new K2Signal(signalNo, userId, signalType));
            return true;
        }

        @Override
        public RiskCaseView createManualReviewCase(String caseNo, Long userId, String bizType, String bizNo, String reason, int riskScore, String ruleCodes, String ruleSnapshot, String operator) {
            caseView = new RiskCaseView(
                    caseNo, userId, bizType, bizNo, null, "KYC", "REVIEW", reason, riskScore, ruleCodes, "REVIEWING", null,
                    null, LocalDateTime.now());
            return caseView;
        }

        @Override
        public List<RiskRuleView> withdrawRules() {
            return rules;
        }

        @Override
        public PageResult<RiskRuleView> pageWithdrawRules(int pageNum, int pageSize) {
            return pageOf(rules, pageNum, pageSize);
        }

        @Override
        public Optional<RiskRuleView> findWithdrawRule(String ruleId) {
            return rules.stream().filter(rule -> rule.ruleId().equals(ruleId)).findFirst();
        }

        @Override
        public List<RiskWithdrawCandidateView> withdrawRuleCandidates(int limit) {
            return withdrawCandidates.stream().limit(limit).toList();
        }

        @Override
        public RiskRuleView createWithdrawRule(String ruleId, String dimension, String conditionText, String action,
                                               String state, int priority, String operator) {
            RiskRuleView created = new RiskRuleView(ruleId, dimension, conditionText, action, state, false,
                    priority, 0L, LocalDateTime.now(), LocalDateTime.now());
            rules.add(created);
            return created;
        }

        @Override
        public Optional<RiskRuleView> updateWithdrawRuleState(String ruleId, long expectedVersion, String state) {
            Optional<RiskRuleView> existing = findWithdrawRule(ruleId);
            existing.filter(rule -> rule.version() == expectedVersion).ifPresent(rule -> {
                rules.remove(rule);
                rules.add(new RiskRuleView(rule.ruleId(), rule.dimension(), rule.conditionText(), rule.action(), state,
                        rule.builtIn(), rule.priority(), rule.version() + 1, rule.createdAt(), LocalDateTime.now()));
            });
            return findWithdrawRule(ruleId).filter(rule -> rule.version() == expectedVersion + 1);
        }

        @Override
        public Optional<RiskRuleView> updateWithdrawRuleConfiguration(
                String ruleId, long expectedVersion, String conditionText, String action, int priority) {
            Optional<RiskRuleView> existing = findWithdrawRule(ruleId);
            existing.filter(rule -> rule.version() == expectedVersion).ifPresent(rule -> {
                rules.remove(rule);
                rules.add(new RiskRuleView(rule.ruleId(), rule.dimension(), conditionText, action, rule.state(),
                        rule.builtIn(), priority, rule.version() + 1, rule.createdAt(), LocalDateTime.now()));
            });
            return findWithdrawRule(ruleId).filter(rule -> rule.version() == expectedVersion + 1);
        }

        @Override
        public List<RiskRouteCountView> withdrawRouteCounts() {
            return List.of(new RiskRouteCountView("manual", "转人工", 10L, "var(--cyan)"));
        }

        @Override
        public List<RiskRuleHitView> withdrawRuleHits(String action, int limit) {
            return withdrawHits.stream()
                    .filter(hit -> action == null || action.isBlank() || "all".equals(action) || hit.action().equals(action))
                    .limit(limit)
                    .toList();
        }

        @Override
        public PageResult<RiskRuleHitView> pageWithdrawRuleHits(String action, int pageNum, int pageSize) {
            List<RiskRuleHitView> filtered = withdrawHits.stream()
                    .filter(hit -> action == null || action.isBlank() || "all".equals(action) || hit.action().equals(action))
                    .toList();
            return pageOf(filtered, pageNum, pageSize);
        }

        @Override
        public void recordWithdrawRuleHit(String withdrawalNo, String userNo, BigDecimal amount, RiskRuleView rule) {
            withdrawHits.add(new RiskRuleHitView(
                    withdrawalNo,
                    userNo,
                    "$" + amount,
                    rule.ruleId(),
                    rule.dimension(),
                    rule.action(),
                    rule.conditionText(),
                    "now"));
        }

        @Override
        public List<RiskArbitrageStatView> arbitrageStats() {
            return List.of(new RiskArbitrageStatView("loopConfirmed", "闭环判定", "1", "sub", "warn"));
        }

        @Override
        public List<RiskArbitrageParamView> arbitrageParams() {
            return arbitrageParams;
        }

        @Override
        public Optional<RiskArbitrageParamView> updateArbitrageParam(String key, long expectedVersion, String value) {
            Optional<RiskArbitrageParamView> existing = arbitrageParams.stream().filter(param -> param.key().equals(key)).findFirst();
            existing.filter(param -> param.version() == expectedVersion).ifPresent(param -> {
                arbitrageParams.remove(param);
                arbitrageParams.add(new RiskArbitrageParamView(
                        param.key(), param.name(), value, param.sub(), param.note(), param.version() + 1));
            });
            return arbitrageParams.stream()
                    .filter(param -> param.key().equals(key) && param.version() == expectedVersion + 1)
                    .findFirst();
        }

        String arbitrageParam(String key) {
            return arbitrageParams.stream()
                    .filter(param -> param.key().equals(key))
                    .findFirst()
                    .map(RiskArbitrageParamView::value)
                    .orElse(null);
        }

        @Override
        public void refreshE3TradeinArbitrageProjection() {
            e3TradeinProjectionRefreshes++;
        }

        @Override
        public List<RiskOpsRepository.TrialCycleDetection> refreshTrialCycleArbitrageProjection(
                int minimumCycles, int windowDays) {
            return trialCycleDetections;
        }

        @Override
        public List<Long> arbitrageSubjectUserIds(String rowId) {
            return "T-318".equals(rowId) ? List.of(1L, 2L) : List.of();
        }

        @Override
        public List<RiskArbitrageRowView> arbitrageRows() {
            return arbitrageRows;
        }

        @Override
        public Optional<RiskArbitrageRowView> findArbitrageRow(String rowId) {
            return arbitrageRows.stream().filter(row -> row.rowId().equals(rowId)).findFirst();
        }

        @Override
        public Optional<RiskArbitrageRowView> updateArbitrageDisposition(String rowId, long expectedVersion, String disposition) {
            Optional<RiskArbitrageRowView> existing = findArbitrageRow(rowId);
            existing.filter(row -> row.version() == expectedVersion && row.disposition() == null).ifPresent(row -> {
                arbitrageRows.remove(row);
                arbitrageRows.add(new RiskArbitrageRowView(
                        row.rowId(), row.viewKey(), row.clusterId(), row.cells(), row.level(), row.actions(), disposition,
                        row.version() + 1, multiAccountClusters.get(row.clusterId()),
                        multiAccountClusterVersions.get(row.clusterId())));
            });
            return findArbitrageRow(rowId).filter(row -> disposition.equals(row.disposition()));
        }

        @Override
        public List<RiskScoreDimensionView> scoringDimensions() {
            return scoreDimensions;
        }

        @Override
        public Optional<RiskScoreModelView> activeScoringModel() {
            return Optional.ofNullable(activeScoreModel);
        }

        @Override
        public Optional<RiskScoreModelView> draftScoringModel() {
            return Optional.ofNullable(draftScoreModel);
        }

        @Override
        public Optional<RiskScoreModelView> scoringModel(long modelVersion) {
            return Optional.ofNullable(historicalScoreModel)
                    .filter(model -> model.version() == modelVersion);
        }

        @Override
        public Optional<RiskScoreModelView> saveScoringModelDraft(
                long expectedVersion, RiskScoringModelDraftRequest request, String operator) {
            if (draftScoreModel == null) {
                if (!java.util.Objects.equals(activeScoreModel.rowVersion(), expectedVersion)) return Optional.empty();
                draftScoreModel = new RiskScoreModelView(
                        activeScoreModel.version() + 1, 0L, "draft", request.weightPercentages(), request.inputSources(),
                        request.scoreMappings(),
                        request.lowMax(), request.highMin(), request.autoEscalateScore(), request.reason(), operator, null,
                        "2026-07-16 10:00:00", null);
            } else {
                if (!java.util.Objects.equals(draftScoreModel.rowVersion(), expectedVersion)) return Optional.empty();
                draftScoreModel = new RiskScoreModelView(
                        draftScoreModel.version(), draftScoreModel.rowVersion() + 1, "draft",
                        request.weightPercentages(), request.inputSources(), request.scoreMappings(),
                        request.lowMax(), request.highMin(),
                        request.autoEscalateScore(), request.reason(), operator, null,
                        draftScoreModel.createdAt(), null);
            }
            return Optional.of(draftScoreModel);
        }

        @Override
        public Optional<RiskScoreModelView> publishScoringModel(
                long expectedVersion, String reason, String operator) {
            if (draftScoreModel == null || !java.util.Objects.equals(draftScoreModel.rowVersion(), expectedVersion)) {
                return Optional.empty();
            }
            archivedScoreModels++;
            activeScoreModel = new RiskScoreModelView(
                    draftScoreModel.version(), draftScoreModel.rowVersion() + 1, "active",
                    draftScoreModel.weights(), draftScoreModel.inputSources(), draftScoreModel.scoreMappings(),
                    draftScoreModel.bandLowMax(), draftScoreModel.bandHighMin(), draftScoreModel.autoEscalateScore(),
                    reason, draftScoreModel.createdBy(), operator, draftScoreModel.createdAt(), "2026-07-16 10:01:00");
            draftScoreModel = null;
            return Optional.of(activeScoreModel);
        }

        int archivedModelCount() {
            return archivedScoreModels;
        }

        @Override
        public List<RiskScoreDimensionView> updateScoringWeights(Map<String, Integer> weights) {
            scoreDimensions.replaceAll(d -> new RiskScoreDimensionView(d.dimKey(), d.name(), d.source(), weights.get(d.dimKey())));
            return scoreDimensions;
        }

        @Override
        public RiskScoreConfigView scoringConfig() {
            return scoreConfig;
        }

        @Override
        public RiskScoreConfigView updateScoringConfig(String key, String value) {
            scoreConfig = switch (key) {
                case "inputSource" -> new RiskScoreConfigView(value, scoreConfig.bandLowMax(), scoreConfig.bandHighMin(), scoreConfig.autoEscalateScore());
                case "bandLowMax" -> new RiskScoreConfigView(scoreConfig.inputSource(), Integer.parseInt(value), scoreConfig.bandHighMin(), scoreConfig.autoEscalateScore());
                case "bandHighMin" -> new RiskScoreConfigView(scoreConfig.inputSource(), scoreConfig.bandLowMax(), Integer.parseInt(value), scoreConfig.autoEscalateScore());
                case "autoEscalateScore" -> new RiskScoreConfigView(scoreConfig.inputSource(), scoreConfig.bandLowMax(), scoreConfig.bandHighMin(), Integer.parseInt(value));
                default -> scoreConfig;
            };
            return scoreConfig;
        }

        @Override
        public List<RiskScoreDistributionView> scoringDistribution() {
            return List.of(
                    new RiskScoreDistributionView("低风险", "< 40", 117108L, 91.2, "var(--success)", "ok"),
                    new RiskScoreDistributionView("中风险", "40-69", 9502L, 7.4, "var(--warning)", "warn"),
                    new RiskScoreDistributionView("高风险", ">= 70", 1790L, 1.4, "var(--danger)", "bad"));
        }

        @Override
        public List<RiskScoreOverrideView> scoreOverrides() {
            return scoreOverrides;
        }

        @Override
        public PageResult<RiskScoreOverrideView> pageScoreOverrides(int pageNum, int pageSize) {
            return pageOf(scoreOverrides, pageNum, pageSize);
        }

        @Override
        public long countActiveScoreOverrides() {
            return scoreOverrides.stream().filter(row -> Boolean.TRUE.equals(row.active())).count();
        }

        void addScoreOverride(RiskScoreOverrideView override) {
            scoreOverrides.add(override);
        }

        @Override
        public Optional<RiskScoreUserView> findScoreUser(String userNo) {
            return scoreUser.userNo().equals(userNo) ? Optional.of(scoreUser) : Optional.empty();
        }

        @Override
        public List<RiskScoreUserSearchView> searchScoreUsers(String keyword, int limit) {
            if (keyword != null && !scoreUser.userNo().toLowerCase().contains(keyword.toLowerCase())) {
                return List.of();
            }
            return List.of(new RiskScoreUserSearchView(
                    scoreUser.userNo(),
                    scoreUser.userNo() + " · 风险评分用户",
                    scoreUser.bandLabel() + " · 模型 " + scoreUser.modelVersion(),
                    scoreUser.modelScore(),
                    scoreUser.effectiveScore(),
                    scoreUser.bandLabel(),
                    scoreUser.bandTone(),
                    scoreUser.overridden()));
        }

        @Override
        public Optional<RiskScoreOverrideView> overrideScore(String userNo, int score, String reason, String operator) {
            return findScoreUser(userNo).flatMap(user -> overrideScore(
                    userNo, user.rowVersion(), score, reason, operator));
        }

        @Override
        public Optional<RiskScoreUserView> findCurrentScoreUser(String userNo) {
            return findScoreUser(userNo);
        }

        @Override
        public Optional<RiskScoreOverrideView> overrideScore(
                String userNo, long expectedVersion, int score, String reason, String operator) {
            Optional<RiskScoreUserView> user = findScoreUser(userNo);
            if (user.isEmpty() || !java.util.Objects.equals(user.get().rowVersion(), expectedVersion)) {
                return Optional.empty();
            }
            String now = "2026-07-16 10:02:00";
            scoreOverrides.replaceAll(row -> row.userNo().equals(userNo)
                    ? new RiskScoreOverrideView(row.userNo(), row.modelScore(), row.overrideScore(),
                    row.reason(), row.operator(), row.timeText(), false) : row);
            RiskScoreOverrideView override = new RiskScoreOverrideView(
                    userNo, user.get().modelScore(), score, reason, operator, now, true);
            scoreOverrides.add(override);
            scoreUser = new RiskScoreUserView(
                    userNo,
                    user.get().modelScore(),
                    score,
                    true,
                    score >= 70 ? "高风险" : score >= 40 ? "中风险" : "低风险",
                    score >= 70 ? "bad" : score >= 40 ? "warn" : "ok",
                    user.get().modelVersion(),
                    user.get().rowVersion() + 1,
                    now,
                    now,
                    user.get().contributions());
            return Optional.of(override);
        }

        @Override
        public Optional<RiskScoreUserView> recomputeScore(String userNo) {
            Optional<RiskScoreUserView> user = findScoreUser(userNo);
            user.ifPresent(v -> scoreUser = new RiskScoreUserView(
                    userNo, v.modelScore(), v.modelScore(), false, "高风险", "bad", v.modelVersion(),
                    v.rowVersion() + 1, "2026-07-16 10:03:00", "2026-07-16 10:03:00", v.contributions()));
            return findScoreUser(userNo);
        }

        @Override
        public Optional<RiskScoreRawInput> scoringInput(String userNo) {
            return findScoreUser(userNo).map(ignored -> new RiskScoreRawInput(
                    userNo, 4, false, 3, false, "REJECTED",
                    5, new BigDecimal("12000"), 3, 2, true));
        }

        @Override
        public Optional<RiskScoreUserView> recomputeScore(
                String userNo, long expectedVersion, RiskScoreModelView model, int modelScore,
                List<RiskScoreContributionView> contributions) {
            Optional<RiskScoreUserView> current = findScoreUser(userNo);
            if (current.isEmpty() || !java.util.Objects.equals(current.get().rowVersion(), expectedVersion)) {
                return Optional.empty();
            }
            String now = "2026-07-16 10:03:00";
            scoreOverrides.replaceAll(row -> row.userNo().equals(userNo)
                    ? new RiskScoreOverrideView(row.userNo(), row.modelScore(), row.overrideScore(),
                    row.reason(), row.operator(), row.timeText(), false) : row);
            int low = model.bandLowMax();
            int high = model.bandHighMin();
            scoreUser = new RiskScoreUserView(
                    userNo, modelScore, modelScore, false,
                    modelScore >= high ? "高风险" : modelScore >= low ? "中风险" : "低风险",
                    modelScore >= high ? "bad" : modelScore >= low ? "warn" : "ok",
                    "k4-v" + model.version(), current.get().rowVersion() + 1, now, now, contributions);
            return Optional.of(scoreUser);
        }

        @Override
        public Optional<RiskScoreUserView> refreshScoreProjection(
                String userNo, long expectedVersion, RiskScoreModelView model, int modelScore,
                List<RiskScoreContributionView> contributions) {
            Optional<RiskScoreUserView> current = findScoreUser(userNo);
            if (current.isEmpty() || !java.util.Objects.equals(current.get().rowVersion(), expectedVersion)) {
                return Optional.empty();
            }
            RiskScoreOverrideView activeOverride = scoreOverrides.stream()
                    .filter(row -> row.userNo().equals(userNo) && row.active())
                    .findFirst().orElse(null);
            int effectiveScore = activeOverride == null ? modelScore : activeOverride.overrideScore();
            int low = model.bandLowMax();
            int high = model.bandHighMin();
            String now = "2026-07-16 10:03:00";
            scoreUser = new RiskScoreUserView(
                    userNo, modelScore, effectiveScore, activeOverride != null,
                    effectiveScore >= high ? "高风险" : effectiveScore >= low ? "中风险" : "低风险",
                    effectiveScore >= high ? "bad" : effectiveScore >= low ? "warn" : "ok",
                    "k4-v" + model.version(), current.get().rowVersion() + 1, now, now, contributions);
            return Optional.of(scoreUser);
        }

        @Override
        public List<String> scoreUserNos() {
            return List.of(scoreUser.userNo());
        }

        @Override
        public boolean transitionK4KycReviewTriggerState(
                String userNo, int effectiveScore, int threshold, String transitionId) {
            boolean above = effectiveScore >= threshold;
            boolean existed = k4KycTriggerAbove.containsKey(userNo);
            boolean previousAbove = k4KycTriggerAbove.getOrDefault(userNo, false);
            k4KycTriggerAbove.put(userNo, above);
            k4KycTriggerThreshold.put(userNo, threshold);
            return above && (!existed || !previousAbove);
        }

        @Override
        public List<String> scoreUserNosNeedingKycTriggerThresholdSync(int threshold, int limit) {
            return !java.util.Objects.equals(k4KycTriggerThreshold.get(scoreUser.userNo()), threshold)
                    ? List.of(scoreUser.userNo()) : List.of();
        }

        @Override
        public Map<String, Object> multiAccountOverview(Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
                                                        Integer whitelistPageNum, Integer whitelistPageSize) {
            int clusterPage = pageNum(clusterPageNum);
            int clusterSize = pageSize(clusterPageSize);
            int whitelistPage = pageNum(whitelistPageNum);
            int whitelistSize = pageSize(whitelistPageSize);
            List<Map<String, String>> clusters = multiAccountLayers.entrySet().stream()
                    .filter(entry -> clusterLayer == null || clusterLayer.equals(entry.getValue()))
                    .map(entry -> Map.of(
                            "id", entry.getKey(),
                            "layer", entry.getValue(),
                            "status", multiAccountClusters.getOrDefault(entry.getKey(), "detected")))
                    .toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("params", multiAccountParams);
            response.put("clusters", new PageResult<>(clusters.size(), clusterPage, clusterSize, page(clusters, clusterPage, clusterSize)));
            response.put("whitelist", new PageResult<>(ipWhitelistRows.size(), whitelistPage, whitelistSize, page(ipWhitelistRows, whitelistPage, whitelistSize)));
            return response;
        }

        @Override
        public Map<String, Object> updateMultiAccountParam(String key, String value) {
            multiAccountParams.put(key, value);
            return multiAccountOverview(1, 5, null, 1, 5);
        }

        @Override
        public Optional<String> multiAccountParamValue(String key) {
            return Optional.ofNullable(multiAccountParams.get(key));
        }

        @Override
        public boolean updateMultiAccountClusterStatus(String clusterId, String status, String reason, String operator) {
            if (!multiAccountLayers.containsKey(clusterId)) {
                return false;
            }
            multiAccountClusters.put(clusterId, status);
            return true;
        }

        @Override
        public Optional<RiskOpsRepository.MultiAccountClusterState> multiAccountClusterState(String clusterId) {
            String state = multiAccountClusters.get(clusterId);
            return state == null ? Optional.empty() : Optional.of(new RiskOpsRepository.MultiAccountClusterState(
                    clusterId, state, "device", 0.8, List.of("U00000001", "U00000002"),
                    multiAccountClusterVersions.getOrDefault(clusterId, 0L)));
        }

        @Override
        public boolean updateMultiAccountClusterStatus(
                String clusterId, String expectedStatus, long expectedVersion,
                String status, String reason, String operator) {
            if (!expectedStatus.equals(multiAccountClusters.get(clusterId))
                    || expectedVersion != multiAccountClusterVersions.getOrDefault(clusterId, 0L)) return false;
            multiAccountClusters.put(clusterId, status);
            multiAccountClusterVersions.put(clusterId, expectedVersion + 1);
            return true;
        }

        @Override
        public boolean updateMultiAccountClusterReviewNote(
                String clusterId, long expectedVersion, String reason, String operator) {
            return multiAccountClusters.containsKey(clusterId);
        }

        String multiAccountParam(String key) {
            return multiAccountParams.get(key);
        }

        @Override
        public void upsertIpWhitelist(String cidr, String note, String operator, String expireText) {
            ipWhitelistStates.put(cidr, new RiskOpsRepository.IpWhitelistState(
                    cidr, note, operator, expireText, true));
            if (!ipWhitelistRows.contains(cidr)) ipWhitelistRows.add(cidr);
        }

        @Override
        public boolean disableIpWhitelist(String cidr, String operator) {
            RiskOpsRepository.IpWhitelistState before = ipWhitelistStates.get(cidr);
            if (before == null) return false;
            ipWhitelistStates.put(cidr, new RiskOpsRepository.IpWhitelistState(
                    cidr, before.note(), operator, before.expireText(), false));
            ipWhitelistRows.remove(cidr);
            return true;
        }

        @Override
        public Optional<RiskOpsRepository.IpWhitelistState> ipWhitelistState(String cidr) {
            return Optional.ofNullable(ipWhitelistStates.get(cidr));
        }

        @Override
        public Map<String, Object> kycReviewOverview(Integer ticketPageNum, Integer ticketPageSize, String ticketFilter) {
            int pageNum = pageNum(ticketPageNum);
            int pageSize = pageSize(ticketPageSize);
            List<Map<String, String>> rows = kycTickets.entrySet().stream()
                    .filter(entry -> ticketFilter == null
                            || ("overdue".equals(ticketFilter) && "overdue".equals(entry.getValue()))
                            || ticketFilter.equals(kycTicketTypes.get(entry.getKey())))
                    .map(entry -> Map.of(
                            "id", entry.getKey(),
                            "st", entry.getValue(),
                            "type", kycTicketTypes.getOrDefault(entry.getKey(), "手动触发"),
                            "infoJson", kycTicketInfoJson.getOrDefault(entry.getKey(), "[]"),
                            "histJson", kycTicketHistJson.getOrDefault(entry.getKey(), "[]")))
                    .toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("params", kycReviewParams);
            response.put("tickets", new PageResult<>(rows.size(), pageNum, pageSize, page(rows, pageNum, pageSize)));
            return response;
        }

        @Override
        public Optional<Map<String, Object>> updateKycReviewParam(String key, String value, long expectedVersion) {
            if (!kycParamVersions.containsKey(key) || kycParamVersions.get(key) != expectedVersion) {
                return Optional.empty();
            }
            kycReviewParams.put(key, value);
            kycParamVersions.put(key, expectedVersion + 1);
            Map<String, Object> result = new LinkedHashMap<>(kycReviewOverview());
            if ("reviewSlaDays".equals(key)) result.put("slaRecomputedTickets", kycTickets.size());
            return Optional.of(result);
        }

        @Override
        public boolean updateKycReviewTicketStatus(String ticketId, String status, long expectedVersion,
                                                   String reasonCode, String reason, String operator) {
            if (!"in-review".equals(kycTickets.get(ticketId))
                    || kycTicketVersions.getOrDefault(ticketId, -1L) != expectedVersion) {
                return false;
            }
            kycTickets.put(ticketId, status);
            kycTicketVersions.put(ticketId, expectedVersion + 1);
            kycDecisionWrites++;
            return true;
        }

        @Override
        public Optional<KycReviewTicketContext> findKycReviewTicket(String ticketId) {
            if (!kycTickets.containsKey(ticketId)) {
                return Optional.empty();
            }
            return Optional.of(new KycReviewTicketContext(
                    ticketId,
                    kycTicketTypes.get(ticketId),
                    kycTicketUsers.get(ticketId),
                    kycTickets.get(ticketId),
                    kycTicketInfoJson.get(ticketId),
                    kycTicketVersions.getOrDefault(ticketId, 0L)));
        }

        @Override
        public Optional<KycReviewTicketContext> findOpenKycReviewTicketByUser(String userNo) {
            manualOpenReadLocked = false;
            return kycTicketUsers.entrySet().stream()
                    .filter(entry -> userNo.equals(entry.getValue()) && "in-review".equals(kycTickets.get(entry.getKey())))
                    .map(Map.Entry::getKey).findFirst().flatMap(this::findKycReviewTicket);
        }

        @Override
        public Optional<KycReviewTicketContext> findOpenKycReviewTicketByUserForUpdate(String userNo) {
            Optional<KycReviewTicketContext> result = findOpenKycReviewTicketByUser(userNo);
            manualOpenReadLocked = true;
            return result;
        }

        @Override
        public boolean mergeOpenKycReviewTicket(String ticketId, long expectedVersion, String reason, String operator) {
            if (simulateUnlockedManualMergeRace && !manualOpenReadLocked) {
                simulateUnlockedManualMergeRace = false;
                kycTicketVersions.computeIfPresent(ticketId, (ignored, version) -> version + 1);
                return false;
            }
            if (!"in-review".equals(kycTickets.get(ticketId))
                    || kycTicketVersions.getOrDefault(ticketId, -1L) != expectedVersion) return false;
            kycTicketVersions.put(ticketId, expectedVersion + 1);
            String info = kycTicketInfoJson.getOrDefault(ticketId, "[]");
            String entry = "[\"触发原因\",\"" + reason.replace("\"", "\\\"") + "\"]";
            kycTicketInfoJson.put(ticketId, "[]".equals(info)
                    ? "[" + entry + "]"
                    : info.substring(0, info.length() - 1) + "," + entry + "]");
            return true;
        }

        @Override
        public void linkKycReviewSource(String ticketId, String sourceDomain, String sourceNo) {
            List<RiskOpsRepository.KycReviewSource> sources = kycTicketSources.computeIfAbsent(
                    ticketId, ignored -> new ArrayList<>());
            RiskOpsRepository.KycReviewSource source = new RiskOpsRepository.KycReviewSource(sourceDomain, sourceNo);
            if (!sources.contains(source)) sources.add(source);
        }

        @Override
        public List<RiskOpsRepository.KycReviewSource> kycReviewSources(String ticketId) {
            return List.copyOf(kycTicketSources.getOrDefault(ticketId, List.of()));
        }

        @Override
        public Map<String, Object> kycAlertSubscription(String operator) {
            return Map.of("alertTypes", kycAlertTypes, "channels", kycAlertChannels,
                    "version", kycAlertSubscriptionVersion);
        }

        @Override
        public Optional<Map<String, Object>> updateKycAlertSubscription(
                String operator, List<String> alertTypes, List<String> channels, long expectedVersion) {
            if (kycAlertSubscriptionVersion != expectedVersion) return Optional.empty();
            kycAlertTypes = new ArrayList<>(alertTypes);
            kycAlertChannels = new ArrayList<>(channels);
            kycAlertSubscriptionVersion++;
            return Optional.of(kycAlertSubscription(operator));
        }

        @Override
        public int generateOverdueKycAlerts() {
            return 0;
        }

        @Override
        public int generateLargeWithdrawalBurstKycAlerts() {
            return 0;
        }

        @Override
        public List<Map<String, Object>> kycAlerts(List<String> alertTypes) {
            return kycAlertRows.stream()
                    .filter(row -> alertTypes.stream().anyMatch(type -> String.valueOf(row.get("eventKey")).startsWith(type + ":")))
                    .toList();
        }

        @Override
        public void createManualKycReviewTicket(String ticketId, String userNo, String reason, String operator) {
            if (simulateManualInsertRace) {
                simulateManualInsertRace = false;
                kycTickets.put("KR-RACE", "in-review");
                kycTicketTypes.put("KR-RACE", "手动触发");
                kycTicketUsers.put("KR-RACE", userNo);
                kycTicketInfoJson.put("KR-RACE", "[]");
                kycTicketVersions.put("KR-RACE", 0L);
                throw new org.springframework.dao.DuplicateKeyException("open user race");
            }
            kycTickets.put(ticketId, "in-review");
            kycTicketTypes.put(ticketId, "手动触发");
            kycTicketUsers.put(ticketId, userNo);
            kycTicketInfoJson.put(ticketId, "[[\"触发原因\",\"手动补触发\"]]");
            kycTicketVersions.put(ticketId, 0L);
        }

        @Override
        public int kycReviewTriggerScore() {
            return scoreLineValue(kycReviewParams.get("reviewTriggerScore"), 85);
        }

        @Override
        public int kycLargeWithdrawReviewUsdt() {
            return scoreLineValue(kycReviewParams.get("largeWithdrawReviewUsdt"), 1000);
        }

        @Override
        public int kycLargeExchangeReviewUsdt() {
            return scoreLineValue(kycReviewParams.getOrDefault("largeExchangeReviewUsdt", kycReviewParams.get("largeWithdrawReviewUsdt")), 1000);
        }

        @Override
        public int kycReviewSlaDays() {
            return scoreLineValue(kycReviewParams.get("reviewSlaDays"), 7);
        }

        @Override
        public boolean hasOpenKycReviewTicket(String userNo) {
            return kycTickets.entrySet().stream()
                    .anyMatch(entry -> userNo.equals(kycTicketUsers.get(entry.getKey()))
                            && !"passed".equals(entry.getValue())
                            && !"rejected".equals(entry.getValue()));
        }

        @Override
        public void createScoreTriggeredKycReviewTicket(String ticketId, String userNo, int score, int threshold, String reason, String operator) {
            if (simulateScoreInsertRace) {
                simulateScoreInsertRace = false;
                kycTickets.put("KR-K4-RACE", "in-review");
                kycTicketTypes.put("KR-K4-RACE", "风险分触发");
                kycTicketUsers.put("KR-K4-RACE", userNo);
                kycTicketInfoJson.put("KR-K4-RACE", "[]");
                kycTicketVersions.put("KR-K4-RACE", 0L);
                throw new org.springframework.dao.DuplicateKeyException("score trigger race");
            }
            kycTickets.put(ticketId, "in-review");
            kycTicketTypes.put(ticketId, "风险分触发");
            kycTicketUsers.put(ticketId, userNo);
            kycTicketInfoJson.put(ticketId, "[[\"触发原因\",\"K4有效风险分 " + score + "\"]]");
            kycTicketVersions.put(ticketId, 0L);
        }

        @Override
        public void createLargeWithdrawalKycReviewTicket(String ticketId, String userNo, BigDecimal amountUsdt, String withdrawalNo,
                                                         String kycStatus, String reason, String operator) {
            kycTickets.put(ticketId, "in-review");
            kycTicketTypes.put(ticketId, "大额提现");
            kycTicketUsers.put(ticketId, userNo);
            kycTicketInfoJson.put(ticketId, "[[\"sourceDomain\",\"D2\"],[\"sourceNo\",\"" + withdrawalNo + "\"]]");
            kycTicketVersions.put(ticketId, 0L);
            linkKycReviewSource(ticketId, "D2", withdrawalNo);
        }

        @Override
        public void createLargeExchangeKycReviewTicket(String ticketId, String userNo, BigDecimal amountUsdt, String exchangeNo,
                                                       String kycStatus, String reason, String operator) {
            kycTickets.put(ticketId, "in-review");
            kycTicketTypes.put(ticketId, "大额兑换");
            kycTicketUsers.put(ticketId, userNo);
            kycTicketInfoJson.put(ticketId, "[[\"sourceDomain\",\"G2\"],[\"sourceNo\",\"" + exchangeNo + "\"]]");
            kycTicketVersions.put(ticketId, 0L);
            linkKycReviewSource(ticketId, "G2", exchangeNo);
        }

        int kycTicketCount() {
            return kycTickets.size();
        }

        String kycTicketStatus(String ticketId) {
            return kycTickets.get(ticketId);
        }

        String kycTicketTypeByUser(String userNo) {
            return kycTicketUsers.entrySet().stream()
                    .filter(entry -> userNo.equals(entry.getValue()))
                    .map(entry -> kycTicketTypes.get(entry.getKey()))
                    .findFirst()
                    .orElse(null);
        }

        private int scoreLineValue(String value, int fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        private int pageNum(Integer value) {
            return value == null || value < 1 ? 1 : value;
        }

        private int pageSize(Integer value) {
            return value == null || value < 1 ? 5 : value;
        }

        private <T> List<T> page(List<T> rows, int pageNum, int pageSize) {
            int from = Math.min(rows.size(), (pageNum - 1) * pageSize);
            int to = Math.min(rows.size(), from + pageSize);
            return rows.subList(from, to);
        }
    }

    private record K2Signal(String signalNo, Long userId, String signalType) {
    }

    private static final class FakeUserKycStatusFacade implements UserKycStatusFacade {
        private String lastUserNo;
        private String lastKycStatus;
        private boolean succeeds = true;
        private final java.util.Set<String> existingUsers = new java.util.HashSet<>(List.of("usr_55B1", "usr_1", "usr_2", "usr_3"));

        @Override
        public boolean userExists(String userNo) {
            return existingUsers.contains(userNo);
        }

        @Override
        public List<Map<String, Object>> reviewCandidates(String keyword, int limit) {
            return existingUsers.stream().limit(limit)
                    .map(userNo -> Map.<String, Object>of("userNo", userNo, "label", userNo, "sub", "ACTIVE", "kycStatus", "PENDING"))
                    .toList();
        }

        @Override
        public boolean updateKycStatusByUserNo(String userNo, String kycStatus, String reason, String operator) {
            lastUserNo = userNo;
            lastKycStatus = kycStatus;
            return succeeds;
        }
    }

    private static final class FakeFinanceWithdrawalKycReviewFacade implements FinanceWithdrawalKycReviewFacade {
        private String releasedWithdrawalNo;
        private String rejectedWithdrawalNo;
        private final List<String> releasedWithdrawalNos = new ArrayList<>();
        private final List<String> rejectedWithdrawalNos = new ArrayList<>();
        private boolean succeeds = true;

        @Override
        public boolean releaseWithdrawalReview(String withdrawalNo, String ticketId, String reason, String operator) {
            releasedWithdrawalNo = withdrawalNo;
            releasedWithdrawalNos.add(withdrawalNo);
            return succeeds;
        }

        @Override
        public boolean rejectWithdrawalReview(String withdrawalNo, String ticketId, String reason, String operator) {
            rejectedWithdrawalNo = withdrawalNo;
            rejectedWithdrawalNos.add(withdrawalNo);
            return succeeds;
        }
    }

    private static final class FakeMarketExchangeKycReviewFacade implements MarketExchangeKycReviewFacade {
        private String releasedExchangeNo;
        private String rejectedExchangeNo;

        @Override
        public boolean releaseExchangeReview(String exchangeNo, String reason, String operator) {
            releasedExchangeNo = exchangeNo;
            return true;
        }

        @Override
        public boolean rejectExchangeReview(String exchangeNo, String reason, String operator) {
            rejectedExchangeNo = exchangeNo;
            return true;
        }
    }
}
