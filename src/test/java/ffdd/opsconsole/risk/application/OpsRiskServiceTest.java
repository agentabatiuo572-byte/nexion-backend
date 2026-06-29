package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.risk.domain.RiskArbitrageParamView;
import ffdd.opsconsole.risk.domain.RiskArbitrageRowView;
import ffdd.opsconsole.risk.domain.RiskArbitrageStatView;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskRouteCountView;
import ffdd.opsconsole.risk.domain.RiskRuleHitView;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.domain.RiskScoreConfigView;
import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreDimensionView;
import ffdd.opsconsole.risk.domain.RiskScoreDistributionView;
import ffdd.opsconsole.risk.domain.RiskScoreOverrideView;
import ffdd.opsconsole.risk.domain.RiskScoreUserSearchView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskArbitrageParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskClusterStatusRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskKycManualReviewRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleCreateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskScoringSourceRequest;
import ffdd.opsconsole.risk.dto.RiskScoringWeightsRequest;
import ffdd.opsconsole.risk.dto.RiskRuleConditionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import ffdd.opsconsole.risk.dto.RiskScoringOverviewQueryRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ffdd.opsconsole.shared.api.PageResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsRiskServiceTest {
    private final FakeRiskOpsRepository riskRepository = new FakeRiskOpsRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsRiskService service = new OpsRiskService(riskRepository, auditLogService);

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
        ApiResult<RiskRuleView> result = service.updateWithdrawRuleState(
                "WR-06",
                "idem-k",
                new RiskRuleStatusRequest("active", "reopen archived rule", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void conditionUpdateAuditsWithdrawRuleChange() {
        ApiResult<RiskRuleView> result = service.updateWithdrawRuleCondition(
                "WR-01",
                "idem-k",
                new RiskRuleConditionRequest("单笔 >= $2,000", "tighten amount line", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conditionText()).isEqualTo("单笔 >= $2,000");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K3_WITHDRAW_RULE_CONDITION_CHANGED");
    }

    @Test
    void conditionUpdateRejectsFreeTextWithdrawRuleCondition() {
        ApiResult<RiskRuleView> result = service.updateWithdrawRuleCondition(
                "WR-02",
                "idem-k",
                new RiskRuleConditionRequest("风险太高就延迟", "bad free text rule condition", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("RULE_CONDITION_INVALID");
    }

    @Test
    void createWithdrawRuleAcceptsStructuredRuleCondition() {
        ApiResult<RiskRuleView> result = service.createWithdrawRule(
                "idem-k-create",
                new RiskRuleCreateRequest("速度", "24h >= 4 笔 或 >= $8,000", "delay", "structured velocity rule", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ruleId()).startsWith("WR-C");
        assertThat(result.getData().conditionText()).isEqualTo("24h >= 4 笔 或 >= $8,000");
    }

    @Test
    void withdrawRuleOverviewReturnsPagedRulesAndHits() {
        ApiResult<Map<String, Object>> result = service.withdrawRuleOverview(
                new RiskRuleOverviewQueryRequest(2, 2, 2, 1, "delay"));

        assertThat(result.getCode()).isZero();
        PageResult<?> rules = (PageResult<?>) result.getData().get("rules");
        PageResult<?> hits = (PageResult<?>) result.getData().get("hits");
        assertThat(rules.getTotal()).isEqualTo(5);
        assertThat(rules.getPageNum()).isEqualTo(2);
        assertThat(rules.getPageSize()).isEqualTo(2);
        assertThat(rules.getRecords()).hasSize(2);
        assertThat(hits.getTotal()).isEqualTo(2);
        assertThat(hits.getPageNum()).isEqualTo(2);
        assertThat(hits.getPageSize()).isEqualTo(1);
        assertThat(hits.getRecords()).hasSize(1);
    }

    @Test
    void arbitrageActionUpdatesDispositionAndAudits() {
        ApiResult<RiskArbitrageRowView> result = service.executeArbitrageAction(
                "T-318",
                "mark",
                "idem-k",
                new RiskArbitrageActionRequest("linked account evidence", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().disposition()).isEqualTo("已标记套利");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K2_ARBITRAGE_MARKED");
    }

    @Test
    void disposedArbitrageRowRejectsRepeatAction() {
        riskRepository.updateArbitrageDisposition("T-318", "已标记套利");

        ApiResult<RiskArbitrageRowView> result = service.executeArbitrageAction(
                "T-318",
                "freeze-cluster",
                "idem-k",
                new RiskArbitrageActionRequest("repeat action", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void arbitrageParamAcceptsStructuredThresholdValue() {
        ApiResult<RiskArbitrageParamView> result = service.updateArbitrageParam(
                "welcomeGiftAnomalyThreshold",
                "idem-k2-param",
                new RiskArbitrageParamUpdateRequest(">= 2 笔 / 实体", "tighten welcome gift anomaly line", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo(">= 2 笔 / 实体");
        assertThat(riskRepository.arbitrageParam("welcomeGiftAnomalyThreshold")).isEqualTo(">= 2 笔 / 实体");
    }

    @Test
    void arbitrageParamRejectsFreeTextThresholdValue() {
        ApiResult<RiskArbitrageParamView> result = service.updateArbitrageParam(
                "welcomeGiftAnomalyThreshold",
                "idem-k2-param",
                new RiskArbitrageParamUpdateRequest("同一人多领就拦", "bad free text threshold", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void scoringWeightsMustSumTo100() {
        ApiResult<Map<String, Object>> result = service.updateScoringWeights(
                "idem-k",
                new RiskScoringWeightsRequest(
                        Map.of(
                                "multiAccount", 25,
                                "arbitrage", 20,
                                "kycState", 20,
                                "withdrawSpeed", 15,
                                "accountAge", 10,
                                "anomaly", 11),
                        "rebalance weights",
                        "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void scoringSourceUsesSharedBackendCatalog() {
        ApiResult<Map<String, Object>> result = service.updateScoringSource(
                "idem-k",
                new RiskScoringSourceRequest("停用 K2 套利维度", "disable source for incident", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.scoringConfig().inputSource()).isEqualTo("停用 K2 套利维度");
    }

    @Test
    void scoringSourceRejectsValuesOutsideCatalog() {
        ApiResult<Map<String, Object>> result = service.updateScoringSource(
                "idem-k",
                new RiskScoringSourceRequest("临时手写来源", "invalid source attempt", "superadmin"));

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
                new RiskScoreOverrideRequest(35, "manual false positive", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().effectiveScore()).isEqualTo(35);
        assertThat(result.getData().overridden()).isTrue();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K4_SCORE_OVERRIDDEN");
    }

    @Test
    void multiAccountParamUpdatesBackendStateAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateMultiAccountParam(
                "sameDeviceThreshold",
                "idem-k1",
                new RiskParamUpdateRequest("4 devices", "tighten multi account threshold", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.multiAccountParam("sameDeviceThreshold")).isEqualTo("4 devices");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K1_MULTI_ACCOUNT_PARAM_CHANGED");
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
    void multiAccountClusterStatusRejectsUnknownCluster() {
        ApiResult<Map<String, Object>> result = service.updateMultiAccountClusterStatus(
                "CL-MISSING",
                "idem-k1",
                new RiskClusterStatusRequest("flagged", "ops review missing cluster", "superadmin"));

        assertThat(result.getCode()).isEqualTo(404);
    }

    @Test
    void kycManualTicketCreatesBackendTicketAndAudits() {
        ApiResult<Map<String, Object>> result = service.createManualKycReviewTicket(
                "idem-k5",
                new RiskKycManualReviewRequest("usr_55B1", "manual escalation from risk ops", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketCount()).isEqualTo(1);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
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

    @Test
    void kycReviewParamRejectsFreeTextTriggerLine() {
        ApiResult<Map<String, Object>> result = service.updateKycReviewParam(
                "reviewTriggerScore",
                "idem-k5-param",
                new RiskParamUpdateRequest("高风险就复审", "reject free text trigger line", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("K5_PARAM_VALUE_INVALID");
    }

    @Test
    void kycReviewDecisionUpdatesBackendTicketAndAudits() {
        riskRepository.createManualKycReviewTicket("KR-1", "usr_55B1", "seed review ticket", "system");

        ApiResult<Map<String, Object>> result = service.decideKycReviewTicket(
                "KR-1",
                "idem-k5",
                new RiskKycReviewDecisionRequest("rejected", "failed manual kyc review", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskRepository.kycTicketStatus("KR-1")).isEqualTo("rejected");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K5_KYC_REVIEW_DECIDED");
    }

    private static final class FakeRiskOpsRepository implements RiskOpsRepository {
        private RiskCaseView caseView = new RiskCaseView(
                "RD-1", 1L, "WITHDRAWAL", "W-1", "US", "L1", "REVIEW", "manual review", 88, "K4", "REVIEWING", null,
                null, LocalDateTime.now().minusHours(1));
        private final List<RiskRuleView> rules = new ArrayList<>(List.of(
                new RiskRuleView("WR-01", "金额", "单笔 >= $1,000", "manual", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-02", "速度", "24h > 3 笔 或 > $5,000", "delay", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-03", "新账户", "注册 < 7 天", "delay", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-04", "地址信誉", "黑名单 / 低信誉地址", "freeze", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-06", "金额", "单笔 >= $500(P1 期旧线)", "manual", "archived", true, LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(20))));
        private final List<RiskRuleHitView> withdrawHits = new ArrayList<>(List.of(
                new RiskRuleHitView("WD-1", "usr_1", "$1,200", "WR-01", "金额", "manual", "单笔大额提现转人工", "今天 10:00"),
                new RiskRuleHitView("WD-2", "usr_2", "$400", "WR-02", "速度", "delay", "24h 提现速度过线延迟", "今天 10:05"),
                new RiskRuleHitView("WD-3", "usr_3", "$500", "WR-02", "速度", "delay", "24h 提现速度过线延迟", "今天 10:10")));
        private final List<RiskArbitrageParamView> arbitrageParams = new ArrayList<>(List.of(
                new RiskArbitrageParamView("trialCycleThreshold", "试用循环异常线", ">= 3 次 / 30 天", "sub", "note"),
                new RiskArbitrageParamView("welcomeGiftAnomalyThreshold", "新人礼异常发放线", ">= 2 笔 / 实体", "sub", "note"),
                new RiskArbitrageParamView("leaderboardVelocityMultiplier", "刷榜增速异常倍数", "> 5x 基线", "sub", "note")));
        private final List<RiskArbitrageRowView> arbitrageRows = new ArrayList<>(List.of(
                new RiskArbitrageRowView("T-318", "trial", "CL-318", List.of("CL-318", "7 次"), 3, List.of("freeze", "flag"), null)));
        private final List<RiskScoreDimensionView> scoreDimensions = new ArrayList<>(List.of(
                new RiskScoreDimensionView("multiAccount", "多账户命中", "来自 K1", 25),
                new RiskScoreDimensionView("arbitrage", "套利信号", "来自 K2", 20),
                new RiskScoreDimensionView("kycState", "实名状态", "来自 C4", 20),
                new RiskScoreDimensionView("withdrawSpeed", "提现速度", "资金事件", 15),
                new RiskScoreDimensionView("accountAge", "账户年龄", "注册时间", 10),
                new RiskScoreDimensionView("anomaly", "异常行为", "行为事件", 10)));
        private RiskScoreConfigView scoreConfig = new RiskScoreConfigView("全部启用", 40, 70, 85);
        private RiskScoreUserView scoreUser = new RiskScoreUserView(
                "usr_55B1", 91, 91, false, "高风险", "bad", "v7", "2 分钟前更新",
                List.of(new RiskScoreContributionView("多账户命中", "是 · 簇 CL-318", 38)));
        private final List<RiskScoreOverrideView> scoreOverrides = new ArrayList<>();
        private final Map<String, String> multiAccountParams = new LinkedHashMap<>(Map.of("sameDeviceThreshold", "3 devices"));
        private final Map<String, String> multiAccountClusters = new LinkedHashMap<>(Map.of("CL-318", "watching"));
        private final Map<String, String> multiAccountLayers = new LinkedHashMap<>(Map.of("CL-318", "device", "CL-309", "ip", "CL-296", "ip"));
        private final List<String> ipWhitelistRows = new ArrayList<>(List.of("103.86.44.0/24", "202.120.0.0/16"));
        private final Map<String, String> kycTickets = new LinkedHashMap<>();
        private final Map<String, String> kycTicketTypes = new LinkedHashMap<>();
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
        public RiskRuleView createWithdrawRule(String ruleId, String dimension, String conditionText, String action, String state, String operator) {
            RiskRuleView created = new RiskRuleView(ruleId, dimension, conditionText, action, state, false, LocalDateTime.now(), LocalDateTime.now());
            rules.add(created);
            return created;
        }

        @Override
        public Optional<RiskRuleView> updateWithdrawRuleState(String ruleId, String state) {
            Optional<RiskRuleView> existing = findWithdrawRule(ruleId);
            existing.ifPresent(rule -> {
                rules.remove(rule);
                rules.add(new RiskRuleView(rule.ruleId(), rule.dimension(), rule.conditionText(), rule.action(), state, rule.builtIn(), rule.createdAt(), LocalDateTime.now()));
            });
            return findWithdrawRule(ruleId);
        }

        @Override
        public Optional<RiskRuleView> updateWithdrawRuleCondition(String ruleId, String conditionText) {
            Optional<RiskRuleView> existing = findWithdrawRule(ruleId);
            existing.ifPresent(rule -> {
                rules.remove(rule);
                rules.add(new RiskRuleView(rule.ruleId(), rule.dimension(), conditionText, rule.action(), rule.state(), rule.builtIn(), rule.createdAt(), LocalDateTime.now()));
            });
            return findWithdrawRule(ruleId);
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
        public List<RiskArbitrageStatView> arbitrageStats() {
            return List.of(new RiskArbitrageStatView("loopConfirmed", "闭环判定", "1", "sub", "warn"));
        }

        @Override
        public List<RiskArbitrageParamView> arbitrageParams() {
            return arbitrageParams;
        }

        @Override
        public Optional<RiskArbitrageParamView> updateArbitrageParam(String key, String value) {
            Optional<RiskArbitrageParamView> existing = arbitrageParams.stream().filter(param -> param.key().equals(key)).findFirst();
            existing.ifPresent(param -> {
                arbitrageParams.remove(param);
                arbitrageParams.add(new RiskArbitrageParamView(param.key(), param.name(), value, param.sub(), param.note()));
            });
            return arbitrageParams.stream().filter(param -> param.key().equals(key)).findFirst();
        }

        String arbitrageParam(String key) {
            return arbitrageParams.stream()
                    .filter(param -> param.key().equals(key))
                    .findFirst()
                    .map(RiskArbitrageParamView::value)
                    .orElse(null);
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
        public Optional<RiskArbitrageRowView> updateArbitrageDisposition(String rowId, String disposition) {
            Optional<RiskArbitrageRowView> existing = findArbitrageRow(rowId);
            existing.ifPresent(row -> {
                arbitrageRows.remove(row);
                arbitrageRows.add(new RiskArbitrageRowView(row.rowId(), row.viewKey(), row.clusterId(), row.cells(), row.level(), row.actions(), disposition));
            });
            return findArbitrageRow(rowId);
        }

        @Override
        public List<RiskScoreDimensionView> scoringDimensions() {
            return scoreDimensions;
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
            Optional<RiskScoreUserView> user = findScoreUser(userNo);
            if (user.isEmpty()) {
                return Optional.empty();
            }
            RiskScoreOverrideView override = new RiskScoreOverrideView(userNo, user.get().modelScore(), score, reason, operator, "刚刚", true);
            scoreOverrides.add(override);
            scoreUser = new RiskScoreUserView(
                    userNo,
                    user.get().modelScore(),
                    score,
                    true,
                    score >= 70 ? "高风险" : score >= 40 ? "中风险" : "低风险",
                    score >= 70 ? "bad" : score >= 40 ? "warn" : "ok",
                    user.get().modelVersion(),
                    user.get().updatedText(),
                    user.get().contributions());
            return Optional.of(override);
        }

        @Override
        public Optional<RiskScoreUserView> recomputeScore(String userNo) {
            Optional<RiskScoreUserView> user = findScoreUser(userNo);
            user.ifPresent(v -> scoreUser = new RiskScoreUserView(
                    userNo, v.modelScore(), v.modelScore(), false, "高风险", "bad", v.modelVersion(), v.updatedText(), v.contributions()));
            return findScoreUser(userNo);
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
        public boolean updateMultiAccountClusterStatus(String clusterId, String status, String reason, String operator) {
            if (!multiAccountLayers.containsKey(clusterId)) {
                return false;
            }
            multiAccountClusters.put(clusterId, status);
            return true;
        }

        String multiAccountParam(String key) {
            return multiAccountParams.get(key);
        }

        @Override
        public void upsertIpWhitelist(String cidr, String note, String operator, String expireText) {
        }

        @Override
        public boolean disableIpWhitelist(String cidr, String operator) {
            return true;
        }

        @Override
        public Map<String, Object> kycReviewOverview(Integer ticketPageNum, Integer ticketPageSize, String ticketFilter) {
            int pageNum = pageNum(ticketPageNum);
            int pageSize = pageSize(ticketPageSize);
            List<Map<String, String>> rows = kycTickets.entrySet().stream()
                    .filter(entry -> ticketFilter == null
                            || ("overdue".equals(ticketFilter) && "overdue".equals(entry.getValue()))
                            || ticketFilter.equals(kycTicketTypes.get(entry.getKey())))
                    .map(entry -> Map.of("id", entry.getKey(), "st", entry.getValue(), "type", kycTicketTypes.getOrDefault(entry.getKey(), "手动触发")))
                    .toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("params", kycReviewParams);
            response.put("tickets", new PageResult<>(rows.size(), pageNum, pageSize, page(rows, pageNum, pageSize)));
            return response;
        }

        @Override
        public Map<String, Object> updateKycReviewParam(String key, String value) {
            kycReviewParams.put(key, value);
            return kycReviewOverview();
        }

        @Override
        public boolean updateKycReviewTicketStatus(String ticketId, String status, String reason, String operator) {
            if (!kycTickets.containsKey(ticketId)) {
                return false;
            }
            kycTickets.put(ticketId, status);
            return true;
        }

        @Override
        public void createManualKycReviewTicket(String ticketId, String userNo, String reason, String operator) {
            kycTickets.put(ticketId, "triggered");
            kycTicketTypes.put(ticketId, "手动触发");
        }

        int kycTicketCount() {
            return kycTickets.size();
        }

        String kycTicketStatus(String ticketId) {
            return kycTickets.get(ticketId);
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
}
