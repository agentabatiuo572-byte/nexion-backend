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
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final OpsRiskService service = new OpsRiskService(
            riskRepository,
            configFacade,
            auditLogService,
            lockMapper,
            idempotencyService,
            superAdminAuthorization,
            userAccountControlFacade,
            eventOutboxService,
            chainAddressReputationGateway);

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
        assertThat(result.getData())
                .containsEntry("serverCanonical", true)
                .containsEntry("domain", "K2")
                .containsKey("sources");
        @SuppressWarnings("unchecked")
        List<RiskArbitrageStatView> stats = (List<RiskArbitrageStatView>) result.getData().get("stats");
        assertThat(stats)
                .extracting(RiskArbitrageStatView::key)
                .containsExactly("loopConfirmed", "loopWarn", "giftBlockedCnt", "boardSignals");
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
        assertThat(result.getData().disposition()).isEqualTo("account_flagged");
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
                "auth.risk.otp_send_cooldown_seconds", "90", "NUMBER", "auth-risk", "K2 OTP gate canonical configuration");
    }

    @Test
    void captchaGateParametersRoundTripThroughTheK2ControlPlane() {
        authenticateK2Admin();
        when(configFacade.activeValue("auth.risk.captcha_always_scenes"))
                .thenReturn(Optional.of("register,login"));
        when(configFacade.activeValue("auth.risk.captcha_after_sends"))
                .thenReturn(Optional.of("3"));

        ApiResult<Map<String, Object>> overview = service.arbitrageOverview();
        @SuppressWarnings("unchecked")
        List<RiskArbitrageParamView> params =
                (List<RiskArbitrageParamView>) overview.getData().get("params");
        assertThat(params).filteredOn(row -> "captchaGate.alwaysScenes".equals(row.key()))
                .extracting(RiskArbitrageParamView::value).containsExactly("register,login");
        assertThat(params).filteredOn(row -> "captchaGate.afterSends".equals(row.key()))
                .extracting(RiskArbitrageParamView::value).containsExactly("3");

        ApiResult<RiskArbitrageParamView> update = service.updateArbitrageParam(
                "captchaGate.alwaysScenes", "idem-k2-captcha-scenes",
                new RiskArbitrageParamUpdateRequest(
                        "reset,register", "change mandatory captcha scenes", "spoofed"));

        assertThat(update.getCode()).isZero();
        assertThat(update.getData().value()).isEqualTo("register,reset");
        verify(configFacade).upsertAdminValue(
                "auth.risk.captcha_always_scenes", "register,reset", "STRING",
                "auth-risk", "K2 OTP gate canonical configuration");
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
        when(configFacade.activeValue("auth.risk.otp_send_cooldown_seconds"))
                .thenAnswer(invocation -> Optional.of(canonical.get()));
        doAnswer(invocation -> {
            canonical.set(invocation.getArgument(1));
            return null;
        }).when(configFacade).upsertAdminValue(
                org.mockito.ArgumentMatchers.eq("auth.risk.otp_send_cooldown_seconds"),
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
                        "multiAccount", 30, "arbitrage", 25,
                        "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 16),
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
                        "multiAccount", new BigDecimal("0.301"), "arbitrage", new BigDecimal("0.249"),
                        "withdrawVelocity", new BigDecimal("0.20"),
                        "accountAge", new BigDecimal("0.10"), "anomalyBehavior", new BigDecimal("0.15")),
                canonicalDraft("ratio weights and mappings").inputSources(), mappings,
                40, 70, 85, "ratio weights and mappings", "spoofed");

        ApiResult<Map<String, Object>> result = service.saveScoringModelDraft("idem-k4-ratio", request);

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.draftScoringModel().orElseThrow().weights().get("multiAccount")).isEqualTo(30);
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
                        "multiAccount", 30,
                        "arbitrage", 25,
                        "withdrawVelocity", 20,
                        "accountAge", 10,
                        "anomalyBehavior", 15),
                Map.of(
                        "multiAccount", true,
                        "arbitrage", true,
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

        ApiResult<RiskScoreUserView> recomputed = service.recomputeScore(
                "usr_55B1",
                "idem-k4-recompute",
                new ffdd.opsconsole.risk.dto.RiskScoreCommandRequest(
                        overridden.getData().rowVersion(), "return to canonical scoring", "spoofed"));
        assertThat(recomputed.getCode()).isZero();
        assertThat(recomputed.getData().overridden()).isFalse();
        assertThat(recomputed.getData().contributions())
                .extracting(RiskScoreContributionView::dimKey)
                .containsExactlyInAnyOrder(
                        "multiAccount", "arbitrage", "withdrawVelocity", "accountAge", "anomalyBehavior");
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
        ApiResult<Map<String, Object>> result = service.recomputeScores(
                "idem-k4-batch",
                new ffdd.opsconsole.risk.dto.RiskScoreBatchCommandRequest(
                        List.of("usr_55B1"), 1L, "batch return to canonical model", "spoofed"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("count", 1);
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(
                audit -> "K4_SCORE_BATCH_RECOMPUTED".equals(audit.getAction())));
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
    void k4BatchRecomputeCanonicalizesCrossUserLockAndHashOrder() {
        authenticateK4Admin(false);
        List<String> observedHashes = new ArrayList<>();
        doAnswer(invocation -> {
            observedHashes.add(invocation.getArgument(2));
            return ApiResult.ok(Map.of("count", 0, "users", List.of()));
        }).when(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K4_SCORE_BATCH_RECOMPUTE"),
                anyString(), anyString(), any(), any());

        service.recomputeScores("idem-k4-batch-forward",
                new ffdd.opsconsole.risk.dto.RiskScoreBatchCommandRequest(
                        List.of("usr_55B2", "usr_55B1"), 1L, "canonical batch lock order", "spoofed"));
        service.recomputeScores("idem-k4-batch-reverse",
                new ffdd.opsconsole.risk.dto.RiskScoreBatchCommandRequest(
                        List.of("usr_55B1", "usr_55B2"), 1L, "canonical batch lock order", "spoofed"));

        assertThat(observedHashes).hasSize(2).allMatch(observedHashes.get(0)::equals);
    }

    @Test
    void k4PublishGlobalPathCanonicalizesRepositoryTargetsBeforeTakingUserLocks() throws Exception {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        RiskScoreModelView model = riskRepository.activeScoringModel().orElseThrow();
        RiskScoreUserView u1 = new RiskScoreUserView(
                "usr_55B1", 10, 10, false, "低风险", "good", "k4-v1", 1L,
                "now", "now", List.of());
        RiskScoreUserView u2 = new RiskScoreUserView(
                "usr_55B2", 20, 20, false, "低风险", "good", "k4-v1", 1L,
                "now", "now", List.of());
        Map<String, RiskScoreUserView> users = Map.of(u1.userNo(), u1, u2.userNo(), u2);
        when(repository.findScoreUser(anyString())).thenAnswer(
                invocation -> Optional.ofNullable(users.get(invocation.getArgument(0))));
        when(repository.scoringInput(anyString())).thenAnswer(invocation -> Optional.of(new RiskScoreRawInput(
                invocation.getArgument(0), 0, false, 0, false,
                0, BigDecimal.ZERO, 180, 0, false)));
        List<String> projectionOrder = new ArrayList<>();
        when(repository.refreshScoreProjection(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(model), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenAnswer(invocation -> {
                    String userNo = invocation.getArgument(0);
                    projectionOrder.add(userNo);
                    return Optional.of(users.get(userNo));
                });
        OpsRiskService publishService = new OpsRiskService(
                repository, configFacade, auditLogService, lockMapper,
                idempotencyService, superAdminAuthorization, userAccountControlFacade,
                eventOutboxService, chainAddressReputationGateway);
        var method = OpsRiskService.class.getDeclaredMethod(
                "recomputeK4Scores", RiskScoreModelView.class, List.class,
                String.class, String.class, String.class);
        method.setAccessible(true);

        method.invoke(publishService, model,
                java.util.Arrays.asList(" usr_55B2 ", "", null, "usr_55B1", "usr_55B2"),
                "publish canonical order", "superadmin", "idem-publish-order");

        assertThat(projectionOrder).containsExactly("usr_55B1", "usr_55B2");
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
    void k4WriteMethodsLeaveThePhysicalTransactionToTheIdempotencyExecutor() {
        assertThat(java.util.Arrays.stream(OpsRiskService.class.getDeclaredMethods())
                .filter(method -> List.of(
                        "saveScoringModelDraft", "publishScoringModel", "restoreScoringModelDraft",
                        "recomputeScores", "overrideScore", "recomputeScore")
                        .contains(method.getName()))
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList())
                .isEmpty();
    }

    @Test
    void k4DeadlockRetriesTheSameIdempotentOverrideOnceWithoutIntermediateFailureAudit() {
        authenticateK4Admin(false);
        AtomicInteger attempts = new AtomicInteger();
        List<String> observedScopes = new ArrayList<>();
        List<String> observedKeys = new ArrayList<>();
        List<String> observedHashes = new ArrayList<>();
        doAnswer(invocation -> {
            observedScopes.add(invocation.getArgument(0));
            observedKeys.add(invocation.getArgument(1));
            observedHashes.add(invocation.getArgument(2));
            if (attempts.incrementAndGet() == 1) {
                throw new org.springframework.dao.DeadlockLoserDataAccessException("simulated k4 deadlock", null);
            }
            return ((java.util.function.Supplier<?>) invocation.getArgument(4)).get();
        }).when(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("K4_SCORE_OVERRIDE:usr_55B1"),
                org.mockito.ArgumentMatchers.eq("idem-k4-deadlock-retry"),
                anyString(), any(), any());

        ApiResult<RiskScoreUserView> result = service.overrideScore(
                "usr_55B1", "idem-k4-deadlock-retry",
                new RiskScoreOverrideRequest(35, 0L, "bounded deadlock retry", "spoofed"));

        assertThat(result.getCode()).isZero();
        assertThat(attempts).hasValue(2);
        assertThat(observedScopes).containsExactly(
                "K4_SCORE_OVERRIDE:usr_55B1", "K4_SCORE_OVERRIDE:usr_55B1");
        assertThat(observedKeys).containsExactly(
                "idem-k4-deadlock-retry", "idem-k4-deadlock-retry");
        assertThat(observedHashes).hasSize(2).doesNotContainNull().allMatch(observedHashes.get(0)::equals);
        verify(auditLogService, never()).recordRequiredInNewTransaction(
                org.mockito.ArgumentMatchers.argThat(audit ->
                        "K4_RISK_SCORING_WRITE_FAILED".equals(audit.getAction())));
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("RISK_SCORE_USER"),
                org.mockito.ArgumentMatchers.eq("usr_55B1"),
                org.mockito.ArgumentMatchers.eq("risk.score_overridden"), any());
    }

    @Test
    void k4PersistentDeadlockFailsClosedAfterTwoAttemptsAndAuditsOnce() {
        authenticateK4Admin(false);
        when(idempotencyService.execute(
                org.mockito.ArgumentMatchers.eq("K4_SCORE_RECOMPUTE:usr_55B1"),
                org.mockito.ArgumentMatchers.eq("idem-k4-deadlock-exhausted"),
                anyString(), any(), any()))
                .thenThrow(new org.springframework.dao.DeadlockLoserDataAccessException("first", null))
                .thenThrow(new org.springframework.dao.DeadlockLoserDataAccessException("second", null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recomputeScore(
                        "usr_55B1", "idem-k4-deadlock-exhausted",
                        new ffdd.opsconsole.risk.dto.RiskScoreCommandRequest(
                                0L, "fail closed after bounded retry", "spoofed")))
                .isInstanceOf(org.springframework.dao.DeadlockLoserDataAccessException.class);

        verify(idempotencyService, times(2)).execute(
                org.mockito.ArgumentMatchers.eq("K4_SCORE_RECOMPUTE:usr_55B1"),
                org.mockito.ArgumentMatchers.eq("idem-k4-deadlock-exhausted"),
                anyString(), any(), any());
        verify(auditLogService, times(1)).recordRequiredInNewTransaction(
                org.mockito.ArgumentMatchers.argThat(audit ->
                        "K4_RISK_SCORING_WRITE_FAILED".equals(audit.getAction())
                                && Boolean.FALSE.equals(((Map<?, ?>) audit.getDetail()).get("businessDataChanged"))));
    }

    private RiskScoringModelDraftRequest canonicalDraft(String reason) {
        return new RiskScoringModelDraftRequest(
                0L,
                Map.of(
                        "multiAccount", 30, "arbitrage", 25,
                        "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 15),
                Map.of(
                        "multiAccount", true, "arbitrage", true,
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
        assertThat(result.getData())
                .containsEntry("serverCanonical", true)
                .containsEntry("domain", "K1")
                .containsKey("sources");
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
                new RiskArbitrageParamView("otpGate.dayLimit", "验证码 24h 发送上限", "10", "sub", "note"),
                new RiskArbitrageParamView("otpGate.otpTtlSeconds", "验证码有效期", "300", "sub", "note"),
                new RiskArbitrageParamView("captchaGate.alwaysScenes", "强制滑块场景", "register", "sub", "note"),
                new RiskArbitrageParamView("captchaGate.afterSends", "发送后触发滑块", "2", "sub", "note")));
        private final List<RiskArbitrageRowView> arbitrageRows = new ArrayList<>(List.of(
                new RiskArbitrageRowView("T-318", "trial", "CL-318", List.of("CL-318", "7 次"), 3, List.of("freeze", "flag"), null)));
        private final List<K2Signal> k2Signals = new ArrayList<>();
        private final List<RiskOpsRepository.TrialCycleDetection> trialCycleDetections = new ArrayList<>();
        private final List<RiskScoreDimensionView> scoreDimensions = new ArrayList<>(List.of(
                new RiskScoreDimensionView("multiAccount", "多账户命中", "来自 K1", 30),
                new RiskScoreDimensionView("arbitrage", "套利信号", "来自 K2", 25),
                new RiskScoreDimensionView("withdrawVelocity", "提现速度", "资金事件", 20),
                new RiskScoreDimensionView("accountAge", "账户年龄", "注册时间", 10),
                new RiskScoreDimensionView("anomalyBehavior", "异常行为", "行为事件", 15)));
        private RiskScoreConfigView scoreConfig = new RiskScoreConfigView("全部启用", 40, 70, 85);
        private RiskScoreUserView scoreUser = new RiskScoreUserView(
                "usr_55B1", 91, 91, false, "高风险", "bad", "k4-v1", 0L,
                "2026-07-16 09:00:00", "2026-07-16 09:00:00",
                List.of(new RiskScoreContributionView(
                        "multiAccount", "多账户命中", true, "簇 CL-318", 100, 25, 25)));
        private RiskScoreModelView activeScoreModel = new RiskScoreModelView(
                1L, 0L, "active",
                Map.of(
                        "multiAccount", 30, "arbitrage", 25,
                        "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 15),
                Map.of(
                        "multiAccount", true, "arbitrage", true,
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
                    caseNo, userId, bizType, bizNo, null, "RISK", "REVIEW", reason, riskScore, ruleCodes, "REVIEWING", null,
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
                    userNo, 4, false, 3, false,
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

}
