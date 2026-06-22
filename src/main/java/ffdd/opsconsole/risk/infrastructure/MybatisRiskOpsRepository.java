package ffdd.opsconsole.risk.infrastructure;


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
import ffdd.opsconsole.risk.domain.RiskScoreDimensionView;
import ffdd.opsconsole.risk.domain.RiskScoreDistributionView;
import ffdd.opsconsole.risk.domain.RiskScoreOverrideView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.shared.api.PageResult;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisRiskOpsRepository implements RiskOpsRepository {
    private final RiskOpsMapper mapper;

    @PostConstruct
    void ensureRiskSchema() {
        mapper.createWithdrawRuleTable();
        mapper.createRouteCountTable();
        mapper.createWithdrawHitTable();
        mapper.createArbitrageStatTable();
        mapper.createArbitrageParamTable();
        mapper.createArbitrageRowTable();
        mapper.createScoreDimensionTable();
        mapper.createScoreConfigTable();
        mapper.createScoreDistributionTable();
        mapper.createScoreUserTable();
        mapper.createScoreContributionTable();
        mapper.createScoreOverrideTable();
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
    public RiskCaseView createManualReviewCase(String caseNo, Long userId, String bizType, String bizNo, String reason, int riskScore, String ruleCodes, String ruleSnapshot, String operator) {
        mapper.insertManualReviewCase(caseNo, userId, bizType, bizNo, reason, riskScore, ruleCodes, ruleSnapshot, operator);
        return findByCaseNo(caseNo).orElseThrow();
    }

    @Override
    public List<RiskRuleView> withdrawRules() {
        return mapper.withdrawRules();
    }

    @Override
    public Optional<RiskRuleView> findWithdrawRule(String ruleId) {
        return Optional.ofNullable(mapper.findWithdrawRule(ruleId));
    }

    @Override
    public RiskRuleView createWithdrawRule(String ruleId, String dimension, String conditionText, String action, String state, String operator) {
        mapper.insertWithdrawRule(ruleId, dimension, conditionText, action, state, false, operator);
        return findWithdrawRule(ruleId).orElseThrow();
    }

    @Override
    public Optional<RiskRuleView> updateWithdrawRuleState(String ruleId, String state) {
        int updated = mapper.updateWithdrawRuleState(ruleId, state);
        return updated == 0 ? Optional.empty() : findWithdrawRule(ruleId);
    }

    @Override
    public Optional<RiskRuleView> updateWithdrawRuleCondition(String ruleId, String conditionText) {
        int updated = mapper.updateWithdrawRuleCondition(ruleId, conditionText);
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
    public List<RiskArbitrageStatView> arbitrageStats() {
        return mapper.arbitrageStats();
    }

    @Override
    public List<RiskArbitrageParamView> arbitrageParams() {
        return mapper.arbitrageParams();
    }

    @Override
    public Optional<RiskArbitrageParamView> updateArbitrageParam(String key, String value) {
        int updated = mapper.updateArbitrageParam(key, value);
        return updated == 0 ? Optional.empty() : Optional.ofNullable(mapper.findArbitrageParam(key));
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
    public Optional<RiskArbitrageRowView> updateArbitrageDisposition(String rowId, String disposition) {
        int updated = mapper.updateArbitrageDisposition(rowId, disposition);
        return updated == 0 ? Optional.empty() : findArbitrageRow(rowId);
    }

    @Override
    public List<RiskScoreDimensionView> scoringDimensions() {
        return mapper.scoreDimensions();
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
                values.getOrDefault("inputSource", "全部启用"),
                intValue(values.get("bandLowMax"), 40),
                intValue(values.get("bandHighMin"), 70),
                intValue(values.get("autoEscalateScore"), 85));
    }

    @Override
    public RiskScoreConfigView updateScoringConfig(String key, String value) {
        mapper.updateScoreConfig(key, value);
        return scoringConfig();
    }

    @Override
    public List<RiskScoreDistributionView> scoringDistribution() {
        long total = mapper.sumScoreDistribution();
        return mapper.scoreDistributionRows().stream()
                .map(row -> new RiskScoreDistributionView(
                        row.band(),
                        row.rangeText(),
                        row.count(),
                        total == 0 ? 0.0 : Math.round((row.count() * 1000.0) / total) / 10.0,
                        row.color(),
                        row.tone()))
                .toList();
    }

    @Override
    public List<RiskScoreOverrideView> scoreOverrides() {
        return mapper.scoreOverrides();
    }

    @Override
    public Optional<RiskScoreUserView> findScoreUser(String userNo) {
        RiskOpsMapper.ScoreUserRecord row = mapper.findScoreUser(userNo);
        if (row == null) {
            return Optional.empty();
        }
        Optional<RiskScoreOverrideView> override = Optional.ofNullable(mapper.activeScoreOverride(row.userNo()));
        int effectiveScore = override.map(RiskScoreOverrideView::overrideScore).orElse(row.modelScore());
        RiskScoreConfigView config = scoringConfig();
        return Optional.of(new RiskScoreUserView(
                row.userNo(),
                row.modelScore(),
                effectiveScore,
                override.isPresent(),
                scoreBandLabel(effectiveScore, config),
                scoreBandTone(effectiveScore, config),
                row.modelVersion(),
                row.updatedText(),
                mapper.scoreContributions(row.userNo()).stream()
                        .map(c -> new RiskScoreContributionView(c.name(), c.evidence(), c.points()))
                        .toList()));
    }

    @Override
    public Optional<RiskScoreOverrideView> overrideScore(String userNo, int score, String reason, String operator) {
        Optional<RiskScoreUserView> user = findScoreUser(userNo);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        mapper.deactivateScoreOverrides(userNo);
        mapper.insertScoreOverride(userNo, user.get().modelScore(), score, reason, operator, "刚刚", true);
        return Optional.ofNullable(mapper.activeScoreOverride(userNo));
    }

    @Override
    public Optional<RiskScoreUserView> recomputeScore(String userNo) {
        mapper.deactivateScoreOverrides(userNo);
        return findScoreUser(userNo);
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
        return new RiskArbitrageRowView(row.rowId(), row.viewKey(), row.clusterId(), cells, row.level(), actions, row.disposition());
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
}
