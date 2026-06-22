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
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskScoringSourceRequest;
import ffdd.opsconsole.risk.dto.RiskScoringWeightsRequest;
import ffdd.opsconsole.risk.dto.RiskRuleConditionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
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

    private static final class FakeRiskOpsRepository implements RiskOpsRepository {
        private RiskCaseView caseView = new RiskCaseView(
                "RD-1", 1L, "WITHDRAWAL", "W-1", "US", "L1", "REVIEW", "manual review", 88, "K4", "REVIEWING", null,
                null, LocalDateTime.now().minusHours(1));
        private final List<RiskRuleView> rules = new ArrayList<>(List.of(
                new RiskRuleView("WR-01", "金额", "单笔 >= $1,000", "manual", "active", true, LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1)),
                new RiskRuleView("WR-06", "金额", "单笔 >= $500(P1 期旧线)", "manual", "archived", true, LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(20))));
        private final List<RiskArbitrageParamView> arbitrageParams = new ArrayList<>(List.of(
                new RiskArbitrageParamView("trialCycleThreshold", "试用循环异常线", ">= 3 次 / 30 天", "sub", "note")));
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
        private RiskCaseQueryRequest lastPageRequest;

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
            return List.of(new RiskRuleHitView("WD-1", "usr_1", "$1,200", "WR-01", "金额", "manual", "今天 10:00"));
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
        public Optional<RiskScoreUserView> findScoreUser(String userNo) {
            return scoreUser.userNo().equals(userNo) ? Optional.of(scoreUser) : Optional.empty();
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
    }
}
