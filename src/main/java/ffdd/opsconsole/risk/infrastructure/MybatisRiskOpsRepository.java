package ffdd.opsconsole.risk.infrastructure;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
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
import ffdd.opsconsole.risk.domain.RiskScoreHistoryView;
import ffdd.opsconsole.risk.domain.RiskScoreDimensionView;
import ffdd.opsconsole.risk.domain.RiskScoreDistributionView;
import ffdd.opsconsole.risk.domain.RiskScoreOverrideView;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.risk.domain.RiskScoreUserSearchView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.application.K4RiskScorer;
import ffdd.opsconsole.risk.application.K4ScoreBackfillInitializer;
import ffdd.opsconsole.risk.domain.RiskWithdrawCandidateView;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelDraftRequest;
import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisRiskOpsRepository implements RiskOpsRepository {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> K4_DIMENSION_KEYS = List.of(
            "multiAccount", "arbitrage", "withdrawVelocity", "accountAge", "anomalyBehavior");
    private static final Map<String, Integer> K4_DEFAULT_WEIGHTS = Map.of(
            "multiAccount", 30, "arbitrage", 25,
            "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 15);
    private static final Map<String, Boolean> K4_DEFAULT_SOURCES = Map.of(
            "multiAccount", true, "arbitrage", true,
            "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true);
    private static final List<RiskArbitrageParamView> RHYTHM_ARBITRAGE_PARAMS = List.of(
            new RiskArbitrageParamView("otpGate.resendSeconds", "验证码重发冷却", "60", "同一手机号两次发送的最小间隔", "单位:秒"),
            new RiskArbitrageParamView("otpGate.captchaAfterSends", "滑块验证触发次数", "2", "24h 发送达到阈值后要求滑块", "单位:次/24h"),
            new RiskArbitrageParamView("otpGate.otpTtlSeconds", "验证码有效期", "300", "验证码签发后的有效时长", "单位:秒"),
            new RiskArbitrageParamView("otpGate.maxVerifyAttempts", "最多输错次数", "5", "超过后验证码立即失效", "单位:次"),
            new RiskArbitrageParamView("otpGate.captchaTicketTtlSeconds", "滑块票据有效期", "120", "一次性滑块票据的有效时长", "单位:秒"));
    private final RiskOpsMapper mapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    @PostConstruct
    void ensureRiskSchema() {
        mapper.createRiskDecisionTable();
        mapper.createRiskSignalTable();
        mapper.createWithdrawRuleTable();
        ensureWithdrawRuleColumns();
        mapper.createRouteCountTable();
        mapper.createWithdrawHitTable();
        ensureWithdrawHitUniqueKey();
        mapper.createArbitrageStatTable();
        mapper.createArbitrageParamTable();
        ensureArbitrageParamVersionColumn();
        mapper.createArbitrageRowTable();
        ensureArbitrageRowVersionColumn();
        mapper.retireObsoleteArbitrageParam("minHoldingMonths");
        ensureK4Schema();
        mapper.createRiskParamTable();
        tryAlter(mapper::addRiskParamVersionColumn);
        mapper.createMultiAccountClusterTable();
        ensureMultiAccountClusterColumns();
        mapper.createIpWhitelistTable();
        ensureK1Params();
    }

    private void ensureK4Schema() {
        mapper.createScoreDimensionTable();
        mapper.createScoreModelTable();
        tryAlter(mapper::addScoreModelMappingColumn);
        mapper.backfillScoreModelMappings(json(K4RiskScorer.DEFAULT_MAPPINGS));
        mapper.createScoreConfigTable();
        mapper.createScoreDistributionTable();
        mapper.createScoreUserTable();
        mapper.createScoreContributionTable();
        mapper.createScoreHistoryTable();
        mapper.createScoreOverrideTable();
        tryAlter(mapper::addScoreUserRowVersionColumn);
        tryAlter(mapper::addScoreUserAsOfColumn);
        tryAlter(mapper::addScoreContributionDimKeyColumn);
        tryAlter(mapper::addScoreContributionHitColumn);
        tryAlter(mapper::addScoreContributionSubScoreColumn);
        tryAlter(mapper::addScoreContributionWeightColumn);
        tryAlter(mapper::addScoreContributionModelVersionColumn);
        tryAlter(mapper::addScoreOverrideActiveKeyColumn);
        mapper.deactivateDuplicateActiveScoreOverrides();
        tryAlter(mapper::addScoreOverrideActiveUniqueKey);
        mapper.deactivateOrphanScoreOverrides();
        mapper.retireOrphanScoreContributions();
        mapper.retireOrphanScoreUsers();
        mapper.ensureAllActiveUsersHaveScoreRows();
        if (mapper.countScoreModels() == 0) {
            mapper.insertInitialScoreModel(
                    json(K4_DEFAULT_WEIGHTS), json(K4_DEFAULT_SOURCES), json(K4RiskScorer.DEFAULT_MAPPINGS));
        }
        long activeVersion = activeScoringModel().map(RiskScoreModelView::version).orElse(1L);
        mapper.backfillContributionModelVersion(activeVersion);
        activeScoringModel().ifPresent(this::projectActiveModel);
    }

    private void tryAlter(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column or index.
        }
    }

    private void ensureWithdrawHitUniqueKey() {
        mapper.deleteDuplicateWithdrawHits();
        try {
            mapper.addWithdrawHitUniqueKey();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this index.
        }
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalCases", mapper.countRiskCases());
        overview.put("manualReview", mapper.countManualReview());
        overview.put("blocked", mapper.countBlocked());
        overview.put("highRisk", mapper.countHighRisk());
        return overview;
    }

    @Override
    public List<RiskCaseView> search(Long userId, String status, String decision, int limit) {
        boolean openOnly = "OPEN".equalsIgnoreCase(status) || "REVIEWING".equalsIgnoreCase(status);
        boolean finalizedOnly = "FINALIZED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status);
        return mapper.searchCases(userId, decision, openOnly, finalizedOnly, limit);
    }

    @Override
    public PageResult<RiskCaseView> pageCases(RiskCaseQueryRequest request) {
        int pageNum = normalizePageNum(request == null ? null : request.pageNum());
        Integer requestedPageSize = request == null ? null : (request.pageSize() == null ? request.limit() : request.pageSize());
        int pageSize = normalizePageSize(requestedPageSize, 50, 200);
        String status = normalizeStatus(request == null ? null : request.status());
        boolean openOnly = "OPEN".equals(status) || "REVIEWING".equals(status);
        boolean finalizedOnly = "FINALIZED".equals(status) || "CLOSED".equals(status);
        String decision = normalizeText(request == null ? null : request.decision());
        long total = mapper.countCasesByQuery(
                request == null ? null : request.userId(),
                decision,
                openOnly,
                finalizedOnly);
        List<RiskCaseView> records = total == 0 ? List.of() : mapper.pageCasesByQuery(
                request == null ? null : request.userId(),
                decision,
                openOnly,
                finalizedOnly,
                (pageNum - 1) * pageSize,
                pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<RiskCaseView> findByCaseNo(String caseNo) {
        return Optional.ofNullable(mapper.findCase(caseNo));
    }

    @Override
    public void updateDecision(String caseNo, String decision, String reason, String operator) {
        mapper.updateDecision(caseNo, decision, reason, operator);
    }

    @Override
    public void recordSignal(String signalNo, Long userId, String signalType, String severity, String evidence, String operator) {
        mapper.insertSignal(signalNo, userId, signalType, severity, evidence, operator);
    }

    @Override
    public boolean recordSignalIfAbsent(
            String signalNo, Long userId, String signalType, String severity, String evidence, String operator) {
        return mapper.insertSignalIfAbsent(signalNo, userId, signalType, severity, evidence, operator) > 0;
    }

    private void ensureWithdrawRuleColumns() {
        try {
            mapper.addWithdrawRulePriorityColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
        try {
            mapper.addWithdrawRuleVersionColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
    }

    private void ensureArbitrageRowVersionColumn() {
        try {
            mapper.addArbitrageRowVersionColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
    }

    private void ensureArbitrageParamVersionColumn() {
        try {
            mapper.addArbitrageParamVersionColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
    }

    private void ensureMultiAccountClusterColumns() {
        try {
            mapper.addMultiAccountClusterEdgesColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
        try {
            mapper.addMultiAccountClusterVersionColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
        try {
            mapper.addMultiAccountClusterFingerprintColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
        try {
            mapper.addMultiAccountClusterThresholdHitColumn();
        } catch (RuntimeException ignored) {
            // Existing deployments may already have this column.
        }
        mapper.widenMultiAccountEvidenceColumns();
    }

    private void ensureK1Params() {
        mapper.upsertK1RiskParam("maxSignupPerIp24h", "同 IP 24h 最大注册数", "3", "个", "范围 1-10", "超过阈值参与多账户聚类", 10);
        mapper.upsertK1RiskParam("maxAccountsPerDevice", "同设备最大账户数", "2", "个", "范围 1-5", "设备指纹关联阈值", 20);
        mapper.upsertK1RiskParam("maxAccountsPerPaymentInstrument", "同支付工具最大账户数", "2", "个", "范围 1-5", "支付工具关联阈值", 30);
        mapper.upsertK1RiskParam("linkWeight", "关联权重", "设备 0.50 · 支付 0.40 · IP 0.10", "", "三项总和必须为 1", "只计算建议强度，不自动冻结", 40);
        mapper.upsertK1RiskParam("clusterFreezeSuggestThreshold", "冻结建议阈值", "0.7", "", "范围 0-1", "达到阈值只给出冻结建议", 50);
        mapper.deactivateLegacyK1RiskParams();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public TamperProjection projectTamperSignal(
            String signalNo, Long userId, String userNo, String evidence, int eventCount,
            boolean feedK4, String operator) {
        String canonicalUserNo = StringUtils.hasText(userNo)
                ? userNo.trim()
                : "U" + String.format(Locale.ROOT, "%08d", userId);
        mapper.insertSignal(signalNo, userId, "TAMPER_DETECTED", "HIGH", evidence, operator);
        if (!feedK4) {
            return new TamperProjection(false, 0, true);
        }
        // Serialize per-user score projection so each event receipt reports only its own applied delta.
        mapper.ensureTamperScoreUser(canonicalUserNo);
        int before = Optional.ofNullable(mapper.lockTamperScoreValue(canonicalUserNo)).orElse(0);
        int requestedPoints = Math.max(1, Math.min(20, eventCount));
        mapper.applyTamperScore(canonicalUserNo, requestedPoints);
        int after = Optional.ofNullable(mapper.scoreValue(canonicalUserNo)).orElse(before);
        int appliedPoints = Math.max(0, after - before);
        mapper.insertTamperScoreContribution(
                canonicalUserNo,
                "服务器篡改拦截事件 " + signalNo,
                appliedPoints);
        // B5 reads the same committed nx_risk_signal rows in its radar feed, so successful insertion
        // is the durable B5 acceptance boundary rather than a locally inferred UI flag.
        return new TamperProjection(true, appliedPoints, true);
    }

    @Override
    public TamperRadarSnapshot tamperRadarSnapshot(java.time.LocalDateTime since) {
        RiskOpsMapper.TamperRadarRecord row = mapper.tamperRadarSnapshot(since);
        if (row == null) {
            return new TamperRadarSnapshot(0, 0, "");
        }
        return new TamperRadarSnapshot(
                Optional.ofNullable(row.signalCount()).orElse(0L),
                Optional.ofNullable(row.accountCount()).orElse(0L),
                Optional.ofNullable(row.latestAt()).orElse(""));
    }

    @Override
    public RiskCaseView createManualReviewCase(String caseNo, Long userId, String bizType, String bizNo, String reason, int riskScore, String ruleCodes, String ruleSnapshot, String operator) {
        mapper.insertManualReviewCase(caseNo, userId, bizType, bizNo, reason, riskScore, ruleCodes, ruleSnapshot, operator);
        return findByCaseNo(caseNo).orElseThrow();
    }

    @Override
    public List<RiskRuleView> withdrawRules() {
        return mapper.withdrawRules();
    }

    @Override
    public PageResult<RiskRuleView> pageWithdrawRules(int pageNum, int pageSize) {
        int offset = Math.max(0, (pageNum - 1) * pageSize);
        return new PageResult<>(mapper.countWithdrawRules(), pageNum, pageSize, mapper.withdrawRulesPage(offset, pageSize));
    }

    @Override
    public Optional<RiskRuleView> findWithdrawRule(String ruleId) {
        return Optional.ofNullable(mapper.findWithdrawRule(ruleId));
    }

    @Override
    public void recordWithdrawRuleHit(String withdrawalNo, String userNo, BigDecimal amount, RiskRuleView rule) {
        if (!StringUtils.hasText(withdrawalNo) || rule == null || !StringUtils.hasText(rule.ruleId())) {
            return;
        }
        mapper.insertWithdrawHit(
                withdrawalNo.trim(),
                StringUtils.hasText(userNo) ? userNo.trim() : "",
                money(amount),
                rule.ruleId(),
                StringUtils.hasText(rule.dimension()) ? rule.dimension() : "提现规则",
                StringUtils.hasText(rule.action()) ? rule.action() : "manual",
                "刚刚");
    }

    @Override
    public List<RiskWithdrawCandidateView> withdrawRuleCandidates(int limit) {
        return mapper.withdrawRuleCandidates(Math.max(1, Math.min(limit, 500)));
    }

    @Override
    public RiskRuleView createWithdrawRule(
            String ruleId, String dimension, String conditionText, String action,
            String state, int priority, String operator) {
        mapper.insertWithdrawRule(ruleId, dimension, conditionText, action, state, false, priority, operator);
        return findWithdrawRule(ruleId).orElseThrow();
    }

    @Override
    public Optional<RiskRuleView> updateWithdrawRuleState(String ruleId, long expectedVersion, String state) {
        int updated = mapper.updateWithdrawRuleState(ruleId, expectedVersion, state);
        return updated == 0 ? Optional.empty() : findWithdrawRule(ruleId);
    }

    @Override
    public Optional<RiskRuleView> updateWithdrawRuleConfiguration(
            String ruleId, long expectedVersion, String conditionText, String action, int priority) {
        int updated = mapper.updateWithdrawRuleConfiguration(ruleId, expectedVersion, conditionText, action, priority);
        return updated == 0 ? Optional.empty() : findWithdrawRule(ruleId);
    }

    @Override
    public List<RiskRouteCountView> withdrawRouteCounts() {
        return mapper.routeCounts();
    }

    @Override
    public List<RiskRuleHitView> withdrawRuleHits(String action, int limit) {
        return mapper.withdrawHits(action, limit);
    }

    @Override
    public PageResult<RiskRuleHitView> pageWithdrawRuleHits(String action, int pageNum, int pageSize) {
        int offset = Math.max(0, (pageNum - 1) * pageSize);
        return new PageResult<>(mapper.countWithdrawHitsByAction(action), pageNum, pageSize, mapper.withdrawHitsPage(action, offset, pageSize));
    }

    @Override
    public List<RiskArbitrageStatView> arbitrageStats() {
        return mapper.arbitrageStats();
    }

    @Override
    public List<RiskArbitrageParamView> arbitrageParams() {
        Map<String, RiskArbitrageParamView> merged = new LinkedHashMap<>();
        RHYTHM_ARBITRAGE_PARAMS.forEach(row -> merged.put(row.key(), row));
        mapper.arbitrageParams().stream()
                .filter(row -> !row.key().startsWith("rewardRisk."))
                .forEach(row -> merged.put(row.key(), row));
        return new ArrayList<>(merged.values());
    }

    @Override
    public Optional<RiskArbitrageParamView> updateArbitrageParam(String key, long expectedVersion, String value) {
        int updated = mapper.updateArbitrageParam(key, expectedVersion, value);
        if (updated == 0) {
            if (mapper.findArbitrageParam(key) != null || expectedVersion != 0L) return Optional.empty();
            RiskArbitrageParamView definition = RHYTHM_ARBITRAGE_PARAMS.stream()
                    .filter(row -> row.key().equals(key))
                    .findFirst()
                    .orElse(null);
            if (definition == null) return Optional.empty();
            int restored = mapper.restoreArbitrageParam(
                    key, definition.name(), value, definition.sub(), definition.note(), expectedVersion);
            if (restored == 0) {
                try {
                    mapper.insertArbitrageParam(key, definition.name(), value, definition.sub(), definition.note(), 1L);
                } catch (RuntimeException duplicateOrRace) {
                    return Optional.empty();
                }
            }
        }
        return Optional.ofNullable(mapper.findArbitrageParam(key));
    }

    @Override
    public void refreshE3TradeinArbitrageProjection() {
        mapper.retireE3TradeinArbitrageRows();
        mapper.upsertE3TradeinArbitrageRows();
    }

    @Override
    public List<TrialCycleDetection> refreshTrialCycleArbitrageProjection(int minimumCycles, int windowDays) {
        int safeMinimum = Math.max(1, Math.min(minimumCycles, 100));
        int safeWindow = Math.max(1, Math.min(windowDays, 365));
        mapper.retireH2TrialCycleRows();
        mapper.upsertH2TrialCycleRows(safeMinimum, safeWindow);
        return mapper.trialCycleDetections(safeMinimum, safeWindow).stream()
                .map(row -> new TrialCycleDetection(
                        row.rowId(), row.userId(), row.clusterId(), row.cycleCount()))
                .toList();
    }

    @Override
    public void refreshWelcomeGiftArbitrageProjection(int minimumSettlements) {
        int safeMinimum = Math.max(1, Math.min(minimumSettlements, 100));
        mapper.retireWelcomeGiftArbitrageRows();
        mapper.upsertWelcomeGiftArbitrageRows(safeMinimum);
    }

    @Override
    public void refreshLeaderboardArbitrageProjection(int velocityMultiplier, boolean inclusive) {
        int safeMultiplier = Math.max(2, Math.min(velocityMultiplier, 20));
        mapper.captureLeaderboardSnapshot();
        mapper.retireLeaderboardArbitrageRows();
        mapper.upsertLeaderboardArbitrageRows(safeMultiplier, inclusive);
    }

    @Override
    public int retireInactiveK2ProjectionSignals() {
        return mapper.retireInactiveK2ProjectionSignals();
    }

    @Override
    public List<Long> arbitrageSubjectUserIds(String rowId) {
        return mapper.arbitrageSubjectUserIds(rowId);
    }

    @Override
    public List<RiskArbitrageRowView> arbitrageRows() {
        return mapper.arbitrageRows().stream().map(this::toArbitrageRow).toList();
    }

    @Override
    public Optional<RiskArbitrageRowView> findArbitrageRow(String rowId) {
        return Optional.ofNullable(mapper.findArbitrageRow(rowId)).map(this::toArbitrageRow);
    }

    @Override
    public Optional<RiskArbitrageRowView> updateArbitrageDisposition(String rowId, long expectedVersion, String disposition) {
        int updated = mapper.updateArbitrageDisposition(rowId, expectedVersion, disposition);
        return updated == 0 ? Optional.empty() : findArbitrageRow(rowId);
    }

    @Override
    public List<RiskScoreDimensionView> scoringDimensions() {
        return mapper.scoreDimensions();
    }

    @Override
    public Optional<RiskScoreModelView> activeScoringModel() {
        return Optional.ofNullable(mapper.activeScoreModel()).map(this::toScoreModel);
    }

    @Override
    public Optional<RiskScoreModelView> draftScoringModel() {
        return Optional.ofNullable(mapper.draftScoreModel()).map(this::toScoreModel);
    }

    @Override
    public List<RiskScoreModelView> scoringModels() {
        return mapper.scoreModels().stream().map(this::toScoreModel).toList();
    }

    @Override
    public Optional<RiskScoreModelView> scoringModel(long modelVersion) {
        return Optional.ofNullable(mapper.scoreModel(modelVersion)).map(this::toScoreModel);
    }

    @Override
    public Optional<RiskScoreModelView> saveScoringModelDraft(
            long expectedVersion, RiskScoringModelDraftRequest request, String operator) {
        mapper.lockActiveScoreModel();
        mapper.lockDraftScoreModel();
        Optional<RiskScoreModelView> active = activeScoringModel();
        Optional<RiskScoreModelView> draft = draftScoringModel();
        if (active.isEmpty()) return Optional.empty();
        if (draft.isPresent()) {
            if (!java.util.Objects.equals(draft.get().rowVersion(), expectedVersion)) return Optional.empty();
            int updated = mapper.updateScoreModelDraft(
                    expectedVersion, json(request.weightPercentages()), json(request.inputSources()),
                    json(request.scoreMappings()),
                    request.lowMax(), request.highMin(), request.autoEscalateScore(),
                    request.reason().trim(), operator);
            return updated == 0 ? Optional.empty() : draftScoringModel();
        }
        if (!java.util.Objects.equals(active.get().rowVersion(), expectedVersion)) return Optional.empty();
        long greatestHistoryVersion = Math.max(
                active.get().version(), java.util.Optional.ofNullable(mapper.maxScoreModelVersion()).orElse(0L));
        mapper.insertScoreModelDraft(
                Math.addExact(greatestHistoryVersion, 1L), json(request.weightPercentages()), json(request.inputSources()),
                json(request.scoreMappings()),
                request.lowMax(), request.highMin(), request.autoEscalateScore(),
                request.reason().trim(), operator);
        return draftScoringModel();
    }

    @Override
    public Optional<RiskScoreModelView> publishScoringModel(
            long expectedVersion, String reason, String operator) {
        mapper.lockActiveScoreModel();
        mapper.lockDraftScoreModel();
        Optional<RiskScoreModelView> draft = draftScoringModel();
        if (draft.isEmpty() || !java.util.Objects.equals(draft.get().rowVersion(), expectedVersion)) {
            return Optional.empty();
        }
        if (mapper.activateScoreModelDraft(expectedVersion, operator, reason.trim()) == 0) return Optional.empty();
        mapper.archiveActiveScoreModel(draft.get().version());
        RiskScoreModelView active = activeScoringModel().orElseThrow();
        projectActiveModel(active);
        return Optional.of(active);
    }

    @Override
    public List<RiskScoreDimensionView> updateScoringWeights(Map<String, Integer> weights) {
        weights.forEach(mapper::updateScoreDimensionWeight);
        return scoringDimensions();
    }

    @Override
    public RiskScoreConfigView scoringConfig() {
        Map<String, String> values = new LinkedHashMap<>();
        mapper.scoreConfigRows().forEach(row -> values.put(row.configKey(), row.valueText()));
        return new RiskScoreConfigView(
                values.getOrDefault("inputSource", ""),
                intValue(values.get("bandLowMax"), 40),
                intValue(values.get("bandHighMin"), 70),
                intValue(values.get("autoEscalateScore"), 85));
    }

    @Override
    public RiskScoreConfigView updateScoringConfig(String key, String value) {
        mapper.upsertScoreConfig(key, value);
        return scoringConfig();
    }

    @Override
    public List<RiskScoreDistributionView> scoringDistribution() {
        RiskScoreConfigView config = scoringConfig();
        RiskOpsMapper.ScoreDistributionCountRecord row = mapper.scoreDistributionCounts(
                config.bandLowMax(), config.bandHighMin());
        long low = row == null || row.lowCount() == null ? 0L : row.lowCount();
        long mid = row == null || row.midCount() == null ? 0L : row.midCount();
        long high = row == null || row.highCount() == null ? 0L : row.highCount();
        long total = low + mid + high;
        return List.of(
                distribution("低风险", "< " + config.bandLowMax(), low, total, "var(--success)", "ok"),
                distribution("中风险", config.bandLowMax() + "-" + (config.bandHighMin() - 1), mid, total, "var(--warning)", "warn"),
                distribution("高风险", ">= " + config.bandHighMin(), high, total, "var(--danger)", "bad"));
    }

    @Override
    public List<RiskScoreOverrideView> scoreOverrides() {
        return mapper.scoreOverrides();
    }

    @Override
    public PageResult<RiskScoreOverrideView> pageScoreOverrides(int pageNum, int pageSize) {
        int offset = Math.max(0, (pageNum - 1) * pageSize);
        return new PageResult<>(mapper.countScoreOverrides(), pageNum, pageSize, mapper.scoreOverridesPage(offset, pageSize));
    }

    @Override
    public long countActiveScoreOverrides() {
        return mapper.countActiveScoreOverrides();
    }

    @Override
    public Optional<RiskScoreUserView> findScoreUser(String userNo) {
        RiskOpsMapper.ScoreUserRecord row = mapper.findScoreUser(userNo);
        return mapScoreUser(row);
    }

    @Override
    public Optional<RiskScoreUserView> findCurrentScoreUser(String userNo) {
        RiskOpsMapper.ScoreUserRecord row = mapper.findCurrentScoreUser(userNo);
        RiskOpsMapper.ScoreModelRecord active = mapper.activeScoreModel();
        if (row == null || active == null
                || !java.util.Objects.equals(row.modelVersion(), "k4-v" + active.modelVersion())
                || active.bandLowMax() == null || active.bandHighMin() == null
                || active.autoEscalateScore() == null
                || active.bandLowMax() < 0 || active.bandLowMax() >= active.bandHighMin()
                || active.bandHighMin() > 100
                || active.autoEscalateScore() < 70 || active.autoEscalateScore() > 100
                || active.autoEscalateScore() < active.bandHighMin()) {
            return Optional.empty();
        }
        return mapScoreUser(row, new RiskScoreConfigView(
                inputSourceLabel(parseMap(active.inputSourcesJson(),
                        new TypeReference<Map<String, Boolean>>() {}, K4_DEFAULT_SOURCES)),
                active.bandLowMax(), active.bandHighMin(), active.autoEscalateScore()));
    }

    private Optional<RiskScoreUserView> mapScoreUser(RiskOpsMapper.ScoreUserRecord row) {
        return mapScoreUser(row, scoringConfig());
    }

    private Optional<RiskScoreUserView> mapScoreUser(
            RiskOpsMapper.ScoreUserRecord row, RiskScoreConfigView config) {
        if (row == null) {
            return Optional.empty();
        }
        Optional<RiskScoreOverrideView> override = Optional.ofNullable(mapper.activeScoreOverride(row.userNo()));
        int effectiveScore = override.map(RiskScoreOverrideView::overrideScore).orElse(row.modelScore());
        return Optional.of(new RiskScoreUserView(
                row.userNo(),
                row.modelScore(),
                effectiveScore,
                override.isPresent(),
                scoreBandLabel(effectiveScore, config),
                scoreBandTone(effectiveScore, config),
                row.modelVersion(),
                row.rowVersion(),
                row.asOf(),
                row.updatedText(),
                mapper.scoreContributions(row.userNo()).stream()
                        .map(c -> new RiskScoreContributionView(
                                c.dimKey(), c.name(), c.hit(), c.evidence(),
                                c.subScore(), c.weightPct(), c.points()))
                        .toList(),
                List.of()));
    }

    @Override
    public List<RiskScoreHistoryView> scoreHistory(String userNo, int limit) {
        return mapper.scoreHistory(userNo, Math.max(1, Math.min(limit, 100))).stream()
                .map(row -> new RiskScoreHistoryView(
                        row.modelVersion(), row.modelScore(), row.effectiveScore(), row.scoreState(),
                        parseMap(row.contributionsJson(), new TypeReference<List<RiskScoreContributionView>>() {}, List.of()),
                        row.reason(), row.operator(), row.createdAt()))
                .toList();
    }

    @Override
    public List<RiskScoreUserSearchView> searchScoreUsers(String keyword, int limit) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        RiskScoreConfigView config = scoringConfig();
        return mapper.searchScoreUsers(normalizedKeyword, Math.max(1, limit)).stream()
                .map(row -> {
                    Optional<RiskScoreOverrideView> override = Optional.ofNullable(mapper.activeScoreOverride(row.userNo()));
                    int effectiveScore = override.map(RiskScoreOverrideView::overrideScore).orElse(row.modelScore());
                    String nickname = StringUtils.hasText(row.nickname()) ? row.nickname().trim() : null;
                    String label = nickname == null ? row.userNo() : row.userNo() + " · " + nickname;
                    String sub = Stream.of(
                                    scoreBandLabel(effectiveScore, config),
                                    "模型分 " + row.modelScore(),
                                    override.isPresent() ? "人工覆盖 " + effectiveScore : null,
                                    row.modelVersion(),
                                    row.phoneMasked(),
                                    row.referralCode())
                            .filter(StringUtils::hasText)
                            .toList()
                            .stream()
                            .reduce((left, right) -> left + " · " + right)
                            .orElse("");
                    return new RiskScoreUserSearchView(
                            row.userNo(),
                            label,
                            sub,
                            row.modelScore(),
                            effectiveScore,
                            scoreBandLabel(effectiveScore, config),
                            scoreBandTone(effectiveScore, config),
                            override.isPresent());
                })
                .toList();
    }

    @Override
    public Optional<RiskScoreOverrideView> overrideScore(String userNo, int score, String reason, String operator) {
        mapper.lockScoreUserForUpdate(userNo);
        Optional<RiskScoreUserView> user = findScoreUser(userNo);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        mapper.deactivateScoreOverrides(userNo);
        mapper.insertScoreOverride(userNo, user.get().modelScore(), score, reason, operator, "刚刚", true);
        return Optional.ofNullable(mapper.activeScoreOverride(userNo));
    }

    @Override
    public Optional<RiskScoreOverrideView> overrideScore(
            String userNo, long expectedVersion, int score, String reason, String operator) {
        mapper.lockScoreUserForUpdate(userNo);
        Optional<RiskScoreUserView> user = findScoreUser(userNo);
        if (user.isEmpty() || mapper.bumpScoreUserVersion(userNo, expectedVersion) == 0) {
            return Optional.empty();
        }
        mapper.deactivateScoreOverrides(userNo);
        mapper.insertScoreOverride(
                userNo, user.get().modelScore(), score, reason, operator,
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), true);
        long modelVersion = activeScoringModel().map(RiskScoreModelView::version).orElse(0L);
        mapper.insertScoreHistory(
                userNo, modelVersion, user.get().modelScore(), score, "manually-overridden",
                json(user.get().contributions()), reason, operator);
        return Optional.ofNullable(mapper.activeScoreOverride(userNo));
    }

    @Override
    public Optional<RiskScoreUserView> recomputeScore(String userNo) {
        mapper.lockScoreUserForUpdate(userNo);
        mapper.deactivateScoreOverrides(userNo);
        return findScoreUser(userNo);
    }

    @Override
    public Optional<RiskScoreRawInput> scoringInput(String userNo) {
        return Optional.ofNullable(mapper.scoreRawInput(userNo)).map(row -> new RiskScoreRawInput(
                row.userNo(), row.multiAccountClusterSize(), row.multiAccountFraud(),
                row.arbitrageSignals(), row.severeArbitrage(),
                row.withdrawalCount24h(), row.withdrawalAmount24h(),
                row.withdrawalCount7d(), row.withdrawalAmount7d(),
                row.withdrawalBaselineDailyCount(), row.withdrawalBaselineDailyAmount(), row.maxWithdrawal24h(),
                row.accountAgeDays(),
                row.anomalySignals(), row.tamperDetected()));
    }

    @Override
    public Optional<RiskScoreUserView> recomputeScore(
            String userNo, long expectedVersion, RiskScoreModelView model, int modelScore,
            List<RiskScoreContributionView> contributions) {
        return updateScoreProjection(userNo, expectedVersion, model, modelScore, contributions, true);
    }

    @Override
    public Optional<RiskScoreUserView> refreshScoreProjection(
            String userNo, long expectedVersion, RiskScoreModelView model, int modelScore,
            List<RiskScoreContributionView> contributions) {
        return updateScoreProjection(userNo, expectedVersion, model, modelScore, contributions, false);
    }

    private Optional<RiskScoreUserView> updateScoreProjection(
            String userNo, long expectedVersion, RiskScoreModelView model, int modelScore,
            List<RiskScoreContributionView> contributions, boolean clearManualOverride) {
        mapper.lockScoreUserForUpdate(userNo);
        RiskScoreOverrideView activeOverride = mapper.activeScoreOverride(userNo);
        if (mapper.updateScoreUserModelIfVersion(
                userNo, expectedVersion, modelScore, "k4-v" + model.version()) == 0) {
            return Optional.empty();
        }
        if (clearManualOverride) {
            mapper.deactivateScoreOverrides(userNo);
        }
        mapper.retireScoreContributions(userNo);
        for (int index = 0; index < contributions.size(); index++) {
            RiskScoreContributionView contribution = contributions.get(index);
            mapper.insertCanonicalScoreContribution(
                    userNo, model.version(), contribution.dimKey(), contribution.name(), Boolean.TRUE.equals(contribution.hit()),
                    contribution.evidence(), contribution.subScore(), contribution.weightPct(), contribution.points(), index);
        }
        int effectiveScore = !clearManualOverride && activeOverride != null
                ? activeOverride.overrideScore()
                : modelScore;
        boolean overridePreserved = !clearManualOverride && activeOverride != null;
        mapper.insertScoreHistory(
                userNo, model.version(), modelScore, effectiveScore,
                overridePreserved ? "manually-overridden" : "model-scored",
                json(contributions),
                overridePreserved ? "事实源刷新（保留人工覆盖）" : "模型重算",
                "system:k4");
        mapper.advanceScoreAsOfToLatestSource(userNo);
        return findScoreUser(userNo);
    }

    @Override
    public List<String> scoreUserNos() {
        return mapper.scoreUserNos();
    }

    @Override
    public List<String> scoreUserNosNeedingProjection(long modelVersion, int limit) {
        return mapper.scoreUserNosNeedingProjection(modelVersion, Math.max(1, Math.min(limit, 1000)));
    }

    @Override
    public long countScoreUsersNeedingProjection(long modelVersion) {
        return mapper.countScoreUsersNeedingProjection(modelVersion);
    }

    @Override
    public int synchronizeScoringUsers() {
        mapper.retireOrphanScoreUsers();
        int synchronizedUsers = mapper.ensureAllActiveUsersHaveScoreRows();
        mapper.deactivateOrphanScoreOverrides();
        mapper.retireOrphanScoreContributions();
        return synchronizedUsers;
    }

    @Override
    public Map<String, Object> multiAccountOverview(Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
                                                     Integer whitelistPageNum, Integer whitelistPageSize) {
        return multiAccountOverview(clusterPageNum, clusterPageSize, clusterLayer, null, "strength_desc",
                whitelistPageNum, whitelistPageSize);
    }

    @Override
    public Map<String, Object> multiAccountOverview(Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
                                                     String clusterStatus, String clusterSort,
                                                     Integer whitelistPageNum, Integer whitelistPageSize) {
        int normalizedClusterPageNum = normalizePageNum(clusterPageNum);
        int normalizedClusterPageSize = normalizePageSize(clusterPageSize, 5, 50);
        String normalizedLayer = StringUtils.hasText(clusterLayer) ? clusterLayer.trim().toLowerCase(Locale.ROOT) : null;
        String normalizedStatus = StringUtils.hasText(clusterStatus) ? clusterStatus.trim().toLowerCase(Locale.ROOT) : null;
        String normalizedSort = "account_desc".equals(clusterSort) ? "account_desc" : "strength_desc";
        int normalizedWhitelistPageNum = normalizePageNum(whitelistPageNum);
        int normalizedWhitelistPageSize = normalizePageSize(whitelistPageSize, 5, 50);

        List<RiskOpsMapper.MultiAccountClusterRecord> allClusters = mapper.multiAccountClusters();
        long activeClusters = allClusters.stream()
                .filter(c -> !"cleared".equalsIgnoreCase(c.status()) && !"released".equalsIgnoreCase(c.status()))
                .count();
        double suggestThreshold = multiAccountConfigValues().entrySet().stream()
                .filter(entry -> "clusterFreezeSuggestThreshold".equals(entry.getKey()))
                .map(Map.Entry::getValue).mapToDouble(value -> parseDouble(value, 0.7d)).findFirst().orElse(0.7d);
        long highClusters = allClusters.stream()
                .filter(c -> c.strength() != null && c.strength() >= suggestThreshold)
                .filter(c -> "detected".equalsIgnoreCase(c.status()) || "flagged".equalsIgnoreCase(c.status()))
                .count();
        long frozenClusters = allClusters.stream().filter(c -> "frozen".equalsIgnoreCase(c.status())).count();
        long frozenAccounts = allClusters.stream()
                .filter(c -> "frozen".equalsIgnoreCase(c.status()))
                .mapToLong(c -> c.n() == null ? 0 : c.n())
                .sum();
        long flaggedAccounts = allClusters.stream()
                .filter(c -> !"cleared".equalsIgnoreCase(c.status()) && !"released".equalsIgnoreCase(c.status()))
                .mapToLong(c -> c.n() == null ? 0 : c.n())
                .sum();
        long clusterTotal = mapper.countMultiAccountClustersByFilter(normalizedLayer, normalizedStatus);
        List<RiskOpsMapper.MultiAccountClusterRecord> clusters = mapper.pageMultiAccountClustersByFilter(
                        normalizedLayer, normalizedStatus, normalizedSort,
                        (normalizedClusterPageNum - 1) * normalizedClusterPageSize,
                        normalizedClusterPageSize).stream()
                .map(this::canonicalizeK1ClusterRecord)
                .toList();
        long whitelistTotal = mapper.countActiveIpWhitelist();
        List<RiskOpsMapper.IpWhitelistRecord> whitelist = mapper.pageIpWhitelist(
                (normalizedWhitelistPageNum - 1) * normalizedWhitelistPageSize,
                normalizedWhitelistPageSize);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("clusterBase", null);
        stats.put("activeClusters", activeClusters);
        stats.put("highBase", null);
        stats.put("highClusters", highClusters);
        stats.put("frozenBase", null);
        stats.put("frozenClusters", frozenClusters);
        stats.put("frozenAccountsBase", null);
        stats.put("frozenAccounts", frozenAccounts);
        stats.put("giftBlockedUsd", null);
        stats.put("giftBlockedCnt", null);
        stats.put("flaggedAccounts", flaggedAccounts);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", stats);
        response.put("params", mapper.riskParams("k1"));
        response.put("clusters", new PageResult<>(clusterTotal, normalizedClusterPageNum, normalizedClusterPageSize, clusters));
        response.put("whitelist", new PageResult<>(whitelistTotal, normalizedWhitelistPageNum, normalizedWhitelistPageSize, whitelist));
        response.put("sources", List.of(
                "nx_user_registration_otp:consumed_client_ip",
                "nx_risk_decision:device_fingerprint",
                "nx_wallet_bank_card:card_token",
                "nx_referral_reward_settlement:welcome_gift",
                "nx_user_wallet:cumulative_deposit_usdt",
                "nx_wallet_ledger:deposit_fallback",
                "nx_admin_risk_multi_account_cluster",
                "nx_admin_risk_ip_whitelist",
                "nx_admin_risk_param:k1"));
        return response;
    }

    @Override
    public Map<String, Object> updateMultiAccountParam(String key, String value) {
        if (mapper.updateRiskParam("k1", key, value) == 0) {
            throw new IllegalStateException("K1_PARAM_NOT_FOUND");
        }
        return multiAccountOverview(1, 5, null, 1, 5);
    }

    @Override
    public Optional<String> multiAccountParamValue(String key) {
        return Optional.ofNullable(mapper.riskParamValue("k1", key));
    }

    @Override
    public boolean updateMultiAccountClusterStatus(String clusterId, String status, String reason, String operator) {
        return mapper.updateMultiAccountClusterStatus(clusterId, status, reason, operator) > 0;
    }

    @Override
    public Optional<MultiAccountClusterState> multiAccountClusterState(String clusterId) {
        RiskOpsMapper.MultiAccountClusterStateRecord row = mapper.multiAccountCluster(clusterId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new MultiAccountClusterState(
                row.id(), row.status(), row.layer(), row.strength() == null ? 0d : row.strength(),
                nodeUserNos(row.nodesJson()), row.version() == null ? 0L : row.version(), row.evidenceFingerprint(),
                Boolean.TRUE.equals(row.thresholdHit())));
    }

    @Override
    public boolean updateMultiAccountClusterStatus(
            String clusterId, String expectedStatus, long expectedVersion,
            String status, String reason, String operator) {
        return mapper.updateMultiAccountClusterStatusIfVersion(
                clusterId, expectedStatus, expectedVersion, status, reason, operator) == 1;
    }

    @Override
    public boolean updateMultiAccountClusterReviewNote(String clusterId, long expectedVersion, String reason, String operator) {
        return mapper.updateMultiAccountClusterReviewNoteIfVersion(clusterId, expectedVersion, reason, operator) == 1;
    }

    @Override
    public void upsertIpWhitelist(String cidr, String note, String operator, String expireText) {
        mapper.upsertIpWhitelist(cidr, note, operator, expireText);
    }

    @Override
    public boolean disableIpWhitelist(String cidr, String operator) {
        return mapper.disableIpWhitelist(cidr, operator) > 0;
    }

    @Override
    public Optional<IpWhitelistState> ipWhitelistState(String cidr) {
        RiskOpsMapper.IpWhitelistRecord row = mapper.ipWhitelistState(cidr);
        if (row == null) return Optional.empty();
        return Optional.of(new IpWhitelistState(
                row.cidr(), row.note(), row.operator(), row.expireText(), Boolean.TRUE.equals(row.active())));
    }

    @Override
    public List<MultiAccountSignalFact> multiAccountSignalFacts() {
        return mapper.multiAccountSignalFacts().stream().map(row -> new MultiAccountSignalFact(
                row.userId(), row.userNo(), row.joinedAt(), row.sponsorUserId(), row.gotWelcomeGift(),
                row.depositCumulativeUsdt(), row.accountStatus(), row.layer(), row.rawKey(), row.maskedKey())).toList();
    }

    @Override
    public Set<String> activeIpWhitelistCidrs() {
        return mapper.activeIpWhitelistCidrs();
    }

    @Override
    public Map<String, String> multiAccountConfigValues() {
        return mapper.riskParams("k1").stream().collect(Collectors.toMap(
                RiskOpsMapper.RiskParamRecord::key,
                RiskOpsMapper.RiskParamRecord::value,
                (first, ignored) -> first,
                LinkedHashMap::new));
    }

    @Override
    public void upsertMultiAccountProjections(List<MultiAccountClusterProjection> projections) {
        if (projections == null) {
            return;
        }
        for (MultiAccountClusterProjection projection : projections) {
            mapper.upsertMultiAccountClusterProjection(
                    projection.clusterId(), projection.maskedKey(), projection.layer(), projection.layerLabel(),
                    projection.accountCount(), projection.strength(), projection.spanText(), projection.note(),
                    projection.evidenceFingerprint(), projection.thresholdHit(),
                    json(projection.giftDuplicates()), json(projection.nodes()), json(projection.edges()));
        }
    }

    @Override
    public void retireMissingDetectedClusters(Set<String> activeClusterIds) {
        mapper.retireMissingDetectedClusters(activeClusterIds == null ? Set.of() : activeClusterIds);
    }

    @Override
    public void clearWhitelistedDetectedClusters(Set<String> clusterIds) {
        mapper.clearWhitelistedDetectedClusters(clusterIds == null ? Set.of() : clusterIds);
    }

    private List<String> nodeUserNos(String nodesJson) {
        if (!StringUtils.hasText(nodesJson)) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(nodesJson);
            List<String> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String userNo = node.path("userNo").asText("");
                    if (!userNo.isBlank()) result.add(userNo);
                }
            }
            return List.copyOf(result);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private RiskOpsMapper.MultiAccountClusterRecord canonicalizeK1ClusterRecord(
            RiskOpsMapper.MultiAccountClusterRecord row) {
        String canonicalNodes = canonicalizeK1NodesJson(row.nodesJson());
        if (java.util.Objects.equals(canonicalNodes, row.nodesJson())) return row;
        return new RiskOpsMapper.MultiAccountClusterRecord(
                row.id(), row.key(), row.layer(), row.layerLabel(), row.n(), row.strength(), row.span(), row.status(),
                row.note(), row.giftsJson(), canonicalNodes, row.edgesJson(), row.reviewNote(), row.version());
    }

    /**
     * Converts the pre-fix Jackson LocalDateTime array shape to the K1 wire contract without accepting it in PC.
     * Malformed, missing, or null timestamps remain untouched so the client still fails closed.
     */
    private String canonicalizeK1NodesJson(String nodesJson) {
        if (!StringUtils.hasText(nodesJson)) return nodesJson;
        try {
            JsonNode root = JSON.readTree(nodesJson);
            if (!root.isArray()) return nodesJson;
            boolean changed = false;
            for (JsonNode node : root) {
                if (!(node instanceof ObjectNode object)) continue;
                JsonNode joinedAt = object.get("joinedAt");
                if (joinedAt == null || joinedAt.isNull()) continue;
                String canonical = canonicalK1JoinedAt(joinedAt);
                if (canonical != null && (!joinedAt.isTextual() || !canonical.equals(joinedAt.textValue()))) {
                    object.put("joinedAt", canonical);
                    changed = true;
                }
            }
            return changed ? JSON.writeValueAsString(root) : nodesJson;
        } catch (JsonProcessingException | RuntimeException ex) {
            return nodesJson;
        }
    }

    private String canonicalK1JoinedAt(JsonNode value) {
        try {
            LocalDateTime parsed;
            if (value.isArray()) {
                if (value.size() < 5 || value.size() > 7) return null;
                for (JsonNode part : value) {
                    if (!part.isIntegralNumber() || !part.canConvertToInt()) return null;
                }
                parsed = LocalDateTime.of(
                        value.get(0).intValue(), value.get(1).intValue(), value.get(2).intValue(),
                        value.get(3).intValue(), value.get(4).intValue(),
                        value.size() >= 6 ? value.get(5).intValue() : 0,
                        value.size() == 7 ? value.get(6).intValue() : 0);
            } else if (value.isTextual() && !value.textValue().isBlank()) {
                parsed = LocalDateTime.parse(value.textValue().trim().replace(' ', 'T'),
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } else {
                return null;
            }
            return parsed.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("K1_PROJECTION_SERIALIZE_FAILED", ex);
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize, int fallback, int max) {
        int value = pageSize == null ? fallback : pageSize;
        if (value < 1) {
            return fallback;
        }
        return Math.min(value, max);
    }

    private String normalizeStatus(String value) {
        return normalizeText(value);
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private RiskArbitrageRowView toArbitrageRow(RiskOpsMapper.RiskArbitrageRowRecord row) {
        List<String> cells = Stream.of(row.cell1(), row.cell2(), row.cell3(), row.cell4(), row.cell5(), row.cell6())
                .filter(StringUtils::hasText)
                .toList();
        List<String> actions = Stream.of(row.actionsCsv().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        return new RiskArbitrageRowView(
                row.rowId(), row.viewKey(), row.clusterId(), cells, row.level(), actions,
                row.disposition(), row.version(), row.clusterStatus(), row.clusterVersion());
    }

    private RiskScoreModelView toScoreModel(RiskOpsMapper.ScoreModelRecord row) {
        return new RiskScoreModelView(
                row.modelVersion(), row.rowVersion(), row.state(),
                parseMap(row.weightsJson(), new TypeReference<Map<String, Integer>>() {}, K4_DEFAULT_WEIGHTS),
                parseMap(row.inputSourcesJson(), new TypeReference<Map<String, Boolean>>() {}, K4_DEFAULT_SOURCES),
                parseMap(row.scoreMappingJson(), new TypeReference<Map<String, Integer>>() {}, K4RiskScorer.DEFAULT_MAPPINGS),
                row.bandLowMax(), row.bandHighMin(), row.autoEscalateScore(), row.reason(),
                row.createdBy(), row.publishedBy(), row.createdAt(), row.publishedAt());
    }

    private <T> T parseMap(String value, TypeReference<T> type, T fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private void projectActiveModel(RiskScoreModelView model) {
        List<String[]> metadata = List.of(
                new String[]{"multiAccount", "多账户", "K1 多账户簇"},
                new String[]{"arbitrage", "套利与刷量", "K2 套利信号"},
                new String[]{"withdrawVelocity", "提现速度", "24 小时提现事实"},
                new String[]{"accountAge", "账户年龄", "用户注册时间"},
                new String[]{"anomalyBehavior", "异常行为", "行为与篡改事件"});
        for (int index = 0; index < metadata.size(); index++) {
            String[] item = metadata.get(index);
            mapper.upsertScoreDimension(item[0], item[1], item[2], model.weights().get(item[0]), index);
        }
        mapper.retireNonCanonicalScoreDimensions(Set.copyOf(K4_DIMENSION_KEYS));
        mapper.upsertScoreConfig("inputSource", inputSourceLabel(model.inputSources()));
        mapper.upsertScoreConfig("bandLowMax", String.valueOf(model.bandLowMax()));
        mapper.upsertScoreConfig("bandHighMin", String.valueOf(model.bandHighMin()));
        mapper.upsertScoreConfig("autoEscalateScore", String.valueOf(model.autoEscalateScore()));
        mapper.upsertScoreConfig("modelVersion", String.valueOf(model.version()));
        mapper.upsertScoreConfig("modelRowVersion", String.valueOf(model.rowVersion()));
        mapper.upsertScoreConfig("modelState", model.state());
    }

    private String inputSourceLabel(Map<String, Boolean> sources) {
        List<String> disabled = K4_DIMENSION_KEYS.stream()
                .filter(key -> !sources.getOrDefault(key, false))
                .toList();
        return disabled.isEmpty() ? "全部启用" : "停用 " + String.join("、", disabled);
    }

    private RiskScoreDistributionView distribution(
            String band, String range, long count, long total, String color, String tone) {
        double percentage = total == 0 ? 0.0 : Math.round((count * 1000.0) / total) / 10.0;
        return new RiskScoreDistributionView(band, range, count, percentage, color, tone);
    }

    private String scoreBandLabel(int score, RiskScoreConfigView config) {
        if (score >= config.bandHighMin()) {
            return "高风险";
        }
        if (score >= config.bandLowMax()) {
            return "中风险";
        }
        return "低风险";
    }

    private String scoreBandTone(int score, RiskScoreConfigView config) {
        if (score >= config.bandHighMin()) {
            return "bad";
        }
        if (score >= config.bandLowMax()) {
            return "warn";
        }
        return "ok";
    }

    private int intValue(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String money(BigDecimal amount) {
        if (amount == null) {
            return "$0";
        }
        return "$" + amount.stripTrailingZeros().toPlainString();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
