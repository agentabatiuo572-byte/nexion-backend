package ffdd.opsconsole.risk.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.risk.domain.KycReviewTicketContext;
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
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
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
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    @PostConstruct
    void ensureRiskSchema() {
        mapper.createRiskDecisionTable();
        mapper.createRiskSignalTable();
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
        mapper.createRiskParamTable();
        mapper.createMultiAccountClusterTable();
        mapper.createIpWhitelistTable();
        mapper.createKycReviewTicketTable();
        mapper.createKycAlertTable();
        if (readTimeSeedPolicy.enabled()) {
            seedRiskDataIfEmpty();
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
                values.getOrDefault("inputSource", readTimeSeedPolicy.enabled() ? "全部启用" : ""),
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

    @Override
    public Map<String, Object> multiAccountOverview(Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
                                                     Integer whitelistPageNum, Integer whitelistPageSize) {
        int normalizedClusterPageNum = normalizePageNum(clusterPageNum);
        int normalizedClusterPageSize = normalizePageSize(clusterPageSize, 5, 50);
        String normalizedLayer = StringUtils.hasText(clusterLayer) ? clusterLayer.trim().toLowerCase(Locale.ROOT) : null;
        int normalizedWhitelistPageNum = normalizePageNum(whitelistPageNum);
        int normalizedWhitelistPageSize = normalizePageSize(whitelistPageSize, 5, 50);

        List<RiskOpsMapper.MultiAccountClusterRecord> allClusters = mapper.multiAccountClusters();
        long activeClusters = allClusters.stream()
                .filter(c -> !"cleared".equalsIgnoreCase(c.status()) && !"released".equalsIgnoreCase(c.status()))
                .count();
        long highClusters = allClusters.stream()
                .filter(c -> c.strength() != null && c.strength() >= 0.7 && !"frozen".equalsIgnoreCase(c.status()))
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
        long clusterTotal = mapper.countMultiAccountClustersByLayer(normalizedLayer);
        List<RiskOpsMapper.MultiAccountClusterRecord> clusters = mapper.pageMultiAccountClusters(
                normalizedLayer,
                (normalizedClusterPageNum - 1) * normalizedClusterPageSize,
                normalizedClusterPageSize);
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
        response.put("sources", List.of("nx_admin_risk_multi_account_cluster", "nx_admin_risk_ip_whitelist", "nx_admin_risk_param:k1"));
        return response;
    }

    @Override
    public Map<String, Object> updateMultiAccountParam(String key, String value) {
        mapper.updateRiskParam("k1", key, value);
        return multiAccountOverview(1, 5, null, 1, 5);
    }

    @Override
    public boolean updateMultiAccountClusterStatus(String clusterId, String status, String reason, String operator) {
        return mapper.updateMultiAccountClusterStatus(clusterId, status, reason, operator) > 0;
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
    public Map<String, Object> kycReviewOverview(Integer ticketPageNum, Integer ticketPageSize, String ticketFilter) {
        int pageNum = normalizePageNum(ticketPageNum);
        int pageSize = normalizePageSize(ticketPageSize, 5, 50);
        int offset = (pageNum - 1) * pageSize;
        long ticketTotal = mapper.countKycTicketsByFilter(ticketFilter);
        List<RiskOpsMapper.KycReviewTicketRecord> tickets = mapper.pageKycReviewTickets(ticketFilter, offset, pageSize);
        long openTickets = mapper.countKycOpenTickets();
        long overdue = mapper.countKycTicketsByStatus("overdue");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("reviewOpenBase", null);
        stats.put("openTickets", openTickets);
        stats.put("reviewOverdue", overdue);
        stats.put("reviewDecidedMonth", null);
        stats.put("reviewDecidedPass", null);
        stats.put("reviewFrozenUsd", null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", stats);
        response.put("params", mapper.riskParams("k5"));
        response.put("tickets", new PageResult<>(ticketTotal, pageNum, pageSize, tickets));
        response.put("alerts", mapper.kycAlerts());
        response.put("sources", List.of("nx_admin_risk_kyc_review_ticket", "nx_admin_risk_kyc_alert", "nx_admin_risk_param:k5"));
        return response;
    }

    @Override
    public Map<String, Object> updateKycReviewParam(String key, String value) {
        mapper.updateRiskParam("k5", key, value);
        return kycReviewOverview();
    }

    @Override
    public boolean updateKycReviewTicketStatus(String ticketId, String status, String reason, String operator) {
        return mapper.updateKycReviewTicketStatus(ticketId, status, reason, operator) > 0;
    }

    @Override
    public Optional<KycReviewTicketContext> findKycReviewTicket(String ticketId) {
        return Optional.ofNullable(mapper.findKycReviewTicket(ticketId))
                .map(row -> new KycReviewTicketContext(row.id(), row.type(), row.user(), row.st(), row.infoJson()));
    }

    @Override
    public void createManualKycReviewTicket(String ticketId, String userNo, String reason, String operator) {
        mapper.insertKycReviewTicket(
                ticketId,
                "手动触发",
                userNo,
                "—",
                "—",
                "待确认(手动补触发)",
                "triggered",
                0.02,
                "剩 7 天",
                "[[\"触发原因\",\"手动补触发:" + escapeJson(reason) + "\"],[\"触发方式\",\"风控人工 · 后续裁决仍需操作确认\"],[\"实名材料\",\"待调取\"]]",
                "[[\"刚刚\",\"手动补触发 · 进入队列\",\"\"]]");
    }

    @Override
    public int kycReviewTriggerScore() {
        return kycParamInt("reviewTriggerScore", 85);
    }

    @Override
    public int kycLargeWithdrawReviewUsdt() {
        return kycParamInt("largeWithdrawReviewUsdt", 1_000);
    }

    @Override
    public int kycLargeExchangeReviewUsdt() {
        return kycParamInt("largeExchangeReviewUsdt", kycLargeWithdrawReviewUsdt());
    }

    @Override
    public boolean hasOpenKycReviewTicket(String userNo) {
        return mapper.countOpenKycTicketsByUser(userNo) > 0;
    }

    @Override
    public void createScoreTriggeredKycReviewTicket(String ticketId, String userNo, int score, int threshold, String reason, String operator) {
        mapper.insertKycReviewTicket(
                ticketId,
                "风险分触发",
                userNo,
                "—",
                "—",
                "待确认(风险分过线)",
                "triggered",
                0.02,
                "剩 7 天",
                "[[\"触发原因\",\"K4有效风险分 " + score + " >= " + threshold + "\"],[\"触发方式\",\"K4风险分人工覆盖\"],[\"覆盖理由\",\"" + escapeJson(reason) + "\"],[\"操作人\",\"" + escapeJson(operator) + "\"]]",
                "[[\"刚刚\",\"K4风险分过线 · 自动进入复审队列\",\"\"]]");
    }

    @Override
    public void createLargeWithdrawalKycReviewTicket(String ticketId, String userNo, BigDecimal amountUsdt, String withdrawalNo,
                                                     String kycStatus, String reason, String operator) {
        int threshold = kycLargeWithdrawReviewUsdt();
        mapper.insertKycReviewTicket(
                ticketId,
                "大额提现",
                userNo,
                money(amountUsdt),
                "—",
                kycText(kycStatus),
                "triggered",
                0.02,
                "剩 7 天",
                "[[\"触发原因\",\"单笔提现 " + money(amountUsdt) + " >= $" + threshold + "\"],"
                        + "[\"提现单\",\"" + escapeJson(withdrawalNo) + " · K5 复审 hold\"],"
                        + "[\"sourceDomain\",\"D2\"],[\"sourceNo\",\"" + escapeJson(withdrawalNo) + "\"],"
                        + "[\"实名状态\",\"" + escapeJson(kycStatus) + "\"],[\"操作人\",\"" + escapeJson(operator) + "\"],"
                        + "[\"触发说明\",\"" + escapeJson(reason) + "\"]]",
                "[[\"刚刚\",\"D2 大额提现触发 K5 复审\",\"warn\"]]");
    }

    @Override
    public void createLargeExchangeKycReviewTicket(String ticketId, String userNo, BigDecimal amountUsdt, String exchangeNo,
                                                   String kycStatus, String reason, String operator) {
        int threshold = kycLargeExchangeReviewUsdt();
        mapper.insertKycReviewTicket(
                ticketId,
                "大额兑换",
                userNo,
                money(amountUsdt),
                "—",
                kycText(kycStatus),
                "triggered",
                0.02,
                "剩 7 天",
                "[[\"触发原因\",\"单笔兑换 " + money(amountUsdt) + " >= $" + threshold + "\"],"
                        + "[\"兑换单\",\"" + escapeJson(exchangeNo) + " · K5 复审 hold\"],"
                        + "[\"sourceDomain\",\"G2\"],[\"sourceNo\",\"" + escapeJson(exchangeNo) + "\"],"
                        + "[\"实名状态\",\"" + escapeJson(kycStatus) + "\"],[\"操作人\",\"" + escapeJson(operator) + "\"],"
                        + "[\"触发说明\",\"" + escapeJson(reason) + "\"]]",
                "[[\"刚刚\",\"G2 大额兑换触发 K5 复审\",\"warn\"]]");
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
            return intFallback(fallback);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return intFallback(fallback);
        }
    }

    private int scoreLineValue(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return intFallback(fallback);
        }
        String digits = value.replaceAll("[^0-9]", "");
        return intValue(digits, fallback);
    }

    private int kycParamInt(String key, int fallback) {
        return mapper.riskParams("k5").stream()
                .filter(param -> key.equals(param.key()))
                .findFirst()
                .map(param -> scoreLineValue(param.value(), fallback))
                .orElseGet(() -> intFallback(fallback));
    }

    private int intFallback(int fallback) {
        return readTimeSeedPolicy.enabled() ? fallback : 0;
    }

    private String money(BigDecimal amount) {
        if (amount == null) {
            return "$0";
        }
        return "$" + amount.stripTrailingZeros().toPlainString();
    }

    private String kycText(String kycStatus) {
        return StringUtils.hasText(kycStatus)
                ? escapeJson(kycStatus.trim() + "(待复审)")
                : "待确认(待复审)";
    }

    private void seedRiskDataIfEmpty() {
        if (mapper.countRiskCases() == 0) {
            seedRiskCases();
        }
        if (mapper.countWithdrawRules() == 0) {
            seedWithdrawRules();
        }
        if (mapper.countRouteCounts() == 0) {
            seedRouteCounts();
        }
        if (mapper.countWithdrawHits() == 0) {
            seedWithdrawHits();
        }
        if (mapper.countArbitrageStats() == 0) {
            seedArbitrageStats();
        }
        if (mapper.countArbitrageParams() == 0) {
            seedArbitrageParams();
        }
        if (mapper.countArbitrageRows() == 0) {
            seedArbitrageRows();
        }
        if (mapper.countScoreDimensions() == 0) {
            seedScoreDimensions();
        }
        if (mapper.countScoreConfig() == 0) {
            seedScoreConfig();
        }
        if (mapper.countScoreDistribution() == 0) {
            seedScoreDistribution();
        }
        if (mapper.countScoreUsers() == 0) {
            seedScoreUsers();
        }
        if (mapper.countScoreOverrides() == 0) {
            seedScoreOverrides();
        }
        if (mapper.countRiskParams("k1") == 0) {
            seedK1Params();
        }
        if (mapper.countMultiAccountClusters() == 0) {
            seedMultiAccountClusters();
        }
        if (mapper.countIpWhitelist() == 0) {
            seedIpWhitelist();
        }
        if (mapper.countRiskParams("k5") == 0) {
            seedK5Params();
        }
        if (mapper.countKycTickets() == 0) {
            seedKycTickets();
        }
        if (mapper.countKycAlerts() == 0) {
            seedKycAlerts();
        }
    }

    private void seedRiskCases() {
        mapper.insertSeedRiskDecision("RD-K1-318", 55L, "MULTI_ACCOUNT_CLUSTER", "CL-318", "SG", "K1", "REVIEW", "设备指纹簇强命中,待人工处置", 91, "K1_MULTI_ACCOUNT,CL-318", "cluster=CL-318;strength=0.88");
        mapper.insertSeedRiskDecision("RD-K1-322", 77L, "MULTI_ACCOUNT_CLUSTER", "CL-322", "SG", "K1", "REVIEW", "支付工具共享簇待人工复核", 74, "K1_PAYMENT_CLUSTER,CL-322", "cluster=CL-322;strength=0.74");
        mapper.insertSeedRiskDecision("RD-K2-T318", 8807L, "ARBITRAGE", "T-318", "US", "K2", "REVIEW", "试用循环 7 次,三层闭环", 88, "K2_TRIAL_LOOP,K1_CLUSTER", "row=T-318;cluster=CL-318");
        mapper.insertSeedRiskDecision("RD-K3-90412", 31L, "WITHDRAW_RULE", "WD-90412", "US", "K3", "REVIEW", "单笔大额提现转人工", 68, "WR-01", "withdrawal=WD-90412;amount=8200");
        mapper.insertSeedRiskDecision("RD-K5-7741", 31L, "KYC_REVIEW", "KR-7741", "US", "K5", "REVIEW", "大额提现触发增强 KYC 复审", 68, "K5_LARGE_WITHDRAW", "ticket=KR-7741;withdrawal=WD-90412");
    }

    private void seedWithdrawRules() {
        mapper.insertWithdrawRule("WR-01", "金额", "单笔 >= $1,000", "manual", "active", true, "seed");
        mapper.insertWithdrawRule("WR-02", "速度", "24h > 3 笔 或 > $5,000", "delay", "active", true, "seed");
        mapper.insertWithdrawRule("WR-03", "新账户", "注册 < 7 天", "delay", "active", true, "seed");
        mapper.insertWithdrawRule("WR-04", "地址信誉", "黑名单 / 低信誉地址", "freeze", "active", true, "seed");
        mapper.insertWithdrawRule("WR-05", "速度", "24h > 8 笔(旧版收紧规则)", "manual", "paused", true, "seed");
        mapper.insertWithdrawRule("WR-06", "金额", "单笔 >= $500(P1 期旧线)", "manual", "archived", true, "seed");
    }

    private void seedRouteCounts() {
        mapper.insertRouteCount("pass", "放行", 11067, "var(--success)");
        mapper.insertRouteCount("delay", "延迟", 1208, "var(--warning)");
        mapper.insertRouteCount("manual", "转人工", 501, "var(--cyan)");
        mapper.insertRouteCount("freeze", "冻结", 64, "var(--danger)");
    }

    private void seedWithdrawHits() {
        mapper.insertWithdrawHit("WD-90412", "usr_31E8", "$8,200", "WR-01", "金额", "manual", "昨天 14:22");
        mapper.insertWithdrawHit("WD-90408", "usr_8807", "$240", "WR-02", "速度", "delay", "今天 14:05");
        mapper.insertWithdrawHit("WD-90402", "usr_9F31", "$310", "WR-03", "新账户", "delay", "今天 13:48");
        mapper.insertWithdrawHit("WD-90396", "usr_2231", "$1,950", "WR-04", "地址信誉", "freeze", "5/20 13:10");
        mapper.insertWithdrawHit("WD-90391", "usr_84F2", "$1,120", "WR-01", "金额", "manual", "今天 12:51");
        mapper.insertWithdrawHit("WD-90391", "usr_84F2", "$1,120", "WR-02", "速度", "delay", "今天 12:51");
        mapper.insertWithdrawHit("WD-90388", "usr_8812", "$95", "WR-02", "速度", "delay", "今天 12:33");
    }

    private void seedArbitrageStats() {
        mapper.insertArbitrageStat("loopConfirmed", "闭环判定(3 层全中)", "4", "本月 · 已联动 K1 冻结 3 簇", "warn");
        mapper.insertArbitrageStat("loopWarn", "预警转人工(2 层可疑)", "17", "30 天滑动窗口内", "");
        mapper.insertArbitrageStat("giftBlocked", "新人礼拦截", "428 笔", "$2,140 + 85,600 NEX 守住", "ok");
        mapper.insertArbitrageStat("boardSignals", "刷榜信号(本期)", "3", "增速 > 5x 基线 · 处置归 F8", "danger");
    }

    private void seedArbitrageParams() {
        mapper.insertArbitrageParam("trialCycleThreshold", "试用循环异常线", ">= 3 次 / 30 天", "同一实体反复开试用超过这条线就报循环信号", "范围 2-10 次。按 K2 持有、H2 读取处理");
        mapper.insertArbitrageParam("welcomeGiftAnomalyThreshold", "新人礼异常发放线", ">= 2 笔 / 实体", "同一实体超 1 笔即异常,拦后面的", "范围 1-5 笔。第 2 笔即算异常,停发后续");
        mapper.insertArbitrageParam("leaderboardVelocityMultiplier", "刷榜增速异常倍数", "> 5x 基线", "超倍数 -> 标记 + 转人工", "范围 2x-20x,下一个快照周期生效");
    }

    private void seedArbitrageRows() {
        mapper.insertArbitrageRow("T-318", "trial", "CL-318", 3, "freeze,flag", "CL-318 · fp_8a3f", "7 次", "9", "$310(影子收益,未入余额)", null, null);
        mapper.insertArbitrageRow("T-322", "trial", "CL-322", 2, "flag", "CL-322 · card_7741", "4 次", "4", "$95", null, null);
        mapper.insertArbitrageRow("T-9921", "trial", null, 1, "flag", "usr_9921(独号)", "3 次", "1", "$28", null, null);
        mapper.insertArbitrageRow("D-3315", "tradein", null, 2, "flag", "usr_3315", "Nexion One #88412", "2026-04-30", "1.3 / 6 个月", "已拦 · $0", null);
        mapper.insertArbitrageRow("D-8807", "tradein", "CL-318", 3, "freeze,flag", "usr_8807(CL-318)", "Nexion One #91230", "2026-05-12", "0.9 / 6 个月", "已拦 · $0", null);
        mapper.insertArbitrageRow("D-2208", "tradein", null, 1, "flag", "usr_2208", "Pro Gen-1 #71022", "2026-03-18", "2.8 / 6 个月", "已拦 · $0", null);
        mapper.insertArbitrageRow("G-318", "gift", "CL-318", 3, "blockgift,freeze", "CL-318", "fp_8a3f", "9 发 / 3 拦", "$45 + 1,800 NEX", "领礼后 24h 零活跃 · 直奔试用", null);
        mapper.insertArbitrageRow("G-322", "gift", "CL-322", 2, "blockgift,flag", "CL-322", "card_7741", "4 发 / 0 拦", "$20 + 800 NEX", "部分账户有真实充值,待人工", null);
        mapper.insertArbitrageRow("B-6611", "board", "CL-330", 3, "boardflag,freeze", "usr_6611", "$4,820", "8.4x", "+41 人 / 24h", "CL-330(新)", null);
        mapper.insertArbitrageRow("B-5102", "board", null, 2, "boardflag", "usr_5102", "$2,150", "5.6x", "+18 人 / 24h", "—", null);
        mapper.insertArbitrageRow("B-1190", "board", null, 1, "boardflag", "usr_1190", "$1,960", "5.1x", "+12 人 / 24h", "—", null);
    }

    private void seedScoreDimensions() {
        mapper.insertScoreDimension("multiAccount", "多账户命中", "来自 K1", 25, 1);
        mapper.insertScoreDimension("arbitrage", "套利信号", "来自 K2", 20, 2);
        mapper.insertScoreDimension("kycState", "实名状态", "来自 C4", 20, 3);
        mapper.insertScoreDimension("withdrawSpeed", "提现速度", "资金事件", 15, 4);
        mapper.insertScoreDimension("accountAge", "账户年龄", "注册时间", 10, 5);
        mapper.insertScoreDimension("anomaly", "异常行为", "行为事件", 10, 6);
    }

    private void seedScoreConfig() {
        mapper.insertScoreConfig("inputSource", "全部启用");
        mapper.insertScoreConfig("bandLowMax", "40");
        mapper.insertScoreConfig("bandHighMin", "70");
        mapper.insertScoreConfig("autoEscalateScore", "85");
    }

    private void seedScoreDistribution() {
        mapper.insertScoreDistribution("low", "低风险", "< 40", 117108, "var(--success)", "ok", 1);
        mapper.insertScoreDistribution("mid", "中风险", "40-69", 9502, "var(--warning)", "warn", 2);
        mapper.insertScoreDistribution("high", "高风险", ">= 70", 1790, "var(--danger)", "bad", 3);
    }

    private void seedScoreUsers() {
        mapper.insertScoreUser("usr_55B1", 91, "v7", "2 分钟前更新");
        mapper.insertScoreContribution("usr_55B1", "多账户命中", "是 · 簇 CL-318", 38, 1);
        mapper.insertScoreContribution("usr_55B1", "套利信号", "是 · 试用循环 x7", 24, 2);
        mapper.insertScoreContribution("usr_55B1", "实名状态", "待复审", 12, 3);
        mapper.insertScoreContribution("usr_55B1", "提现速度", "24h 3 笔", 9, 4);
        mapper.insertScoreContribution("usr_55B1", "账户年龄", "22 天(新)", 5, 5);
        mapper.insertScoreContribution("usr_55B1", "异常行为", "深夜批量操作", 3, 6);
        mapper.insertScoreUser("usr_84F2", 72, "v7", "2 分钟前更新");
        mapper.insertScoreContribution("usr_84F2", "提现速度", "24h 5 笔 · $9,400", 34, 1);
        mapper.insertScoreContribution("usr_84F2", "账户年龄", "89 天", 6, 2);
        mapper.insertScoreContribution("usr_84F2", "异常行为", "大额夜间提现", 32, 3);
        mapper.insertScoreUser("usr_19C7", 18, "v7", "2 分钟前更新");
        mapper.insertScoreContribution("usr_19C7", "提现速度", "正常", 6, 1);
        mapper.insertScoreContribution("usr_19C7", "账户年龄", "134 天", 2, 2);
        mapper.insertScoreContribution("usr_19C7", "异常行为", "无", 10, 3);
    }

    private void seedScoreOverrides() {
        mapper.insertScoreOverride("usr_2231", 88, 35, "线下核实为代理商集中收款,非套现", "risklead_h", "5/28", true);
        mapper.insertScoreOverride("usr_9921", 42, 75, "客服举报线索:疑似收购账户,临时压高待查", "risklead_h", "5/22", true);
        mapper.insertScoreOverride("usr_5102", 61, 20, "误判已解除(CL-296 夫妻共用卡)", "risklead_h", "5/24", true);
    }

    private void seedK1Params() {
        mapper.insertRiskParam("k1", "maxSignupPerIp24h", "同一 IP,24 小时内最多注册", "3 个号", null, "超过就拒绝注册", "范围 1-10 · 超限直接拒绝注册", 1);
        mapper.insertRiskParam("k1", "maxAccountsPerDevice", "同一台设备最多绑定", "2 个号", null, "按设备指纹识别", "范围 1-5 · 超限拒绝注册/绑上级", 2);
        mapper.insertRiskParam("k1", "maxAccountsPerPaymentInstrument", "同一支付工具最多绑定", "2 个号", null, "同卡/同钱包算同一人", "范围 1-5 · 超限标记并停发新人礼", 3);
        mapper.insertRiskParam("k1", "linkWeight", "关联强度怎么算(权重)", "设备 0.5 · 支付 0.4 · IP 0.1", null, "IP 权重最低,防合租网络误判", "只对之后的新判定生效,不追溯老簇", 4);
        mapper.insertRiskParam("k1", "clusterFreezeSuggestThreshold", "标红建议冻结线", "0.7", null, "只提醒,不自动冻结", "范围 0-1 · 冻结仍要理由确认", 5);
    }

    private void seedMultiAccountClusters() {
        mapper.insertMultiAccountCluster("CL-318", "fp_8a3f...c2", "device", "设备指纹", 12, 0.88, "5/02 - 5/19(17 天)", "flagged", "12 个账户共用 2 台设备 + 3 张卡,注册集中在 17 天内,9 个号领过新人礼。建议批量冻结。",
                "[[\"G-2241\",\"已发 9 笔 · $45 + 1,800 NEX\",\"异常 · 已停发后续\"],[\"G-2238\",\"拦截 3 笔\",\"已拦截\"]]",
                "[[\"usr_55B1\",\"5/19\",\"NX-5512\",\"是\",\"$1,240\",\"frozen\"],[\"usr_8812\",\"5/17\",\"NX-8821\",\"是\",\"$200\",\"flagged\"],[\"usr_8810\",\"5/16\",\"NX-8821\",\"是\",\"$180\",\"flagged\"],[\"usr_8807\",\"5/12\",\"NX-8821\",\"是\",\"$99\",\"flagged\"],[\"usr_8801\",\"5/02\",\"主号\",\"否\",\"$3,400\",\"flagged\"]]");
        mapper.insertMultiAccountCluster("CL-322", "card_7741", "payment", "支付工具", 5, 0.74, "4/28 - 5/30(32 天)", "detected", "5 个账户共用同一张卡充值,设备各不相同。可能是家庭共用,也可能是分散养号。",
                "[[\"G-2255\",\"已发 4 笔 · $20 + 800 NEX\",\"待判定\"]]",
                "[[\"usr_77D4\",\"5/30\",\"NX-7741\",\"是\",\"$310\",\"detected\"],[\"usr_7702\",\"5/21\",\"NX-7741\",\"是\",\"$150\",\"detected\"],[\"usr_7698\",\"5/12\",\"NX-7741\",\"是\",\"$95\",\"detected\"],[\"usr_7691\",\"4/28\",\"主号\",\"否\",\"$890\",\"detected\"]]");
        mapper.insertMultiAccountCluster("CL-309", "ip_103.86...", "ip", "IP", 8, 0.31, "3/12 - 5/28(77 天)", "cleared", "8 个账户同一出口 IP,但设备、支付全不重合。命中校园网白名单,系统已判定正常。",
                "[]",
                "[[\"usr_4410\",\"5/28\",\"NX-1190\",\"是\",\"$520\",\"cleared\"],[\"usr_4321\",\"4/02\",\"NX-0029\",\"是\",\"$1,100\",\"cleared\"],[\"usr_4015\",\"3/12\",\"—\",\"否\",\"$2,300\",\"cleared\"]]");
        mapper.insertMultiAccountCluster("CL-301", "fp_77be...9d", "device", "设备指纹", 6, 0.81, "4/11 - 4/15(4 天)", "frozen", "6 个账户同一台设备 4 天内连开,已于 4/16 操作确认批量冻结。",
                "[[\"G-2102\",\"已发 5 笔 · 已转余额追回流程(C3)\",\"已处置\"]]",
                "[[\"usr_6201\",\"4/15\",\"NX-5512\",\"是\",\"$80\",\"frozen\"],[\"usr_6195\",\"4/13\",\"NX-5512\",\"是\",\"$75\",\"frozen\"],[\"usr_6188\",\"4/11\",\"主号\",\"否\",\"$1,600\",\"frozen\"]]");
        mapper.insertMultiAccountCluster("CL-296", "ip_45.77...", "ip", "IP", 4, 0.42, "5/01 - 5/22(21 天)", "released", "曾因同 IP + 同支付误判冻结,核实为夫妻共用卡,5/24 操作确认解除。",
                "[]",
                "[[\"usr_5102\",\"5/22\",\"NX-3188\",\"是\",\"$430\",\"released\"],[\"usr_5099\",\"5/01\",\"—\",\"否\",\"$960\",\"released\"]]");
    }

    private void seedIpWhitelist() {
        mapper.upsertIpWhitelist("103.86.44.0/24", "新加坡某联合办公空间", "risklead_h", "2026-12-31");
        mapper.upsertIpWhitelist("202.120.0.0/16", "上海某高校校园网", "risklead_h", "长期");
    }

    private void seedK5Params() {
        mapper.insertRiskParam("k5", "largeWithdrawReviewUsdt", "大额提现复审线", ">= $1,000", null, "命中 -> 生成复审工单 + 提现单冻结", "范围 $100-$50,000。与 K3/D2 是独立参数", 1);
        mapper.insertRiskParam("k5", "cumulativeKycThresholdUsdt", "累计金额触发线", "$100", "终身累计", "累计兑换过线 -> 实名升级复审", "范围 $50-$1,000 · 终身累计兑换过线即触发", 2);
        mapper.insertRiskParam("k5", "reviewSlaDays", "复审时限", "7", "个工作日", "超时自动告警 · 大额可延至 15 天", "范围 1-15 天;超时自动告警 + 升级", 3);
        mapper.insertRiskParam("k5", "reviewTriggerScore", "风险分触发线", ">= 85", "分", "K4 风险分过线 -> 自动建复审工单", "范围 70-100 · 对接 K4 自动升级线", 4);
    }

    private void seedKycTickets() {
        mapper.insertKycReviewTicket("KR-7741", "大额提现", "usr_31E8", "$8,200", "—", "已通过(待复审)", "in-review", 0.18, "剩 6 天",
                "[[\"触发原因\",\"单笔提现 $8,200 >= $1,000\"],[\"提现单\",\"WD-90412 · 复审 hold\"],[\"实名材料\",\"服务商档案 #SB-44102\"],[\"风险评分(K4)\",\"68 · 中\"],[\"账户年龄\",\"181 天\"],[\"历史提现\",\"41 笔 · 全部正常\"]]",
                "[[\"昨天 14:22\",\"触发 · 单笔大额(K3 命中同刻)\",\"\"],[\"昨天 14:22\",\"提现单 WD-90412 进入复审 hold\",\"warn\"],[\"今天 10:05\",\"材料初核完成 · 等待裁决\",\"\"]]");
        mapper.insertKycReviewTicket("KR-7738", "累计过线", "usr_77D4", "—", "$112 / $100", "快速实名(待升级)", "in-review", 0.68, "剩 2 天",
                "[[\"触发原因\",\"终身累计兑换 $112 过 $100 线\"],[\"关联簇\",\"CL-322(K1 · 待判)\"],[\"实名材料\",\"快速实名($1 验证)\"],[\"风险评分(K4)\",\"11 · 低\"],[\"账户年龄\",\"11 天\"],[\"历史提现\",\"1 笔\"]]",
                "[[\"6/03 16:40\",\"触发 · 累计过线\",\"\"] ,[\"6/05 11:20\",\"要求补充材料 · 已通知用户\",\"warn\"]]");
        mapper.insertKycReviewTicket("KR-7702", "大额提现", "usr_2231", "$1,950", "—", "已通过(待复审)", "overdue", 1.0, "已超时",
                "[[\"触发原因\",\"单笔 $1,950 + 地址信誉冻结(K3)\"],[\"提现单\",\"WD-90396 · 已冻结\"],[\"实名材料\",\"服务商档案 #SB-39871\"],[\"风险评分(K4)\",\"88 -> 35(人工覆盖)\"],[\"账户年龄\",\"203 天\"],[\"备注\",\"评分覆盖原因:代理商集中收款\"]]",
                "[[\"5/20 13:10\",\"触发 · 大额 + 低信誉地址\",\"\"] ,[\"5/29 09:00\",\"超 SLA 告警 · 升级风控主管\",\"bad\"]]");
        mapper.insertKycReviewTicket("KR-7729", "大额兑换", "usr_84F2", "$2,400", "—", "已通过(待复审)", "triggered", 0.05, "剩 7 天",
                "[[\"触发原因\",\"单日兑换 $2,400\"],[\"风险评分(K4)\",\"72 · 高\"],[\"账户年龄\",\"89 天\"],[\"历史兑换\",\"12 笔\"]]",
                "[[\"今天 08:15\",\"触发 · 大额兑换\",\"\"]]");
    }

    private void seedKycAlerts() {
        mapper.insertKycAlert("bad", "复审超时", "KR-7702(usr_2231 · $1,950)超 7 个工作日未裁决,已自动升级给风控主管", "5/29 09:00");
        mapper.insertKycAlert("warn", "批量大额集中", "过去 1 小时 5 笔 >= $1,000 提现来自同一推荐链(NX-8821),已联动 K1 查簇", "昨天 22:41");
        mapper.insertKycAlert("warn", "累计过线高峰", "本周 38 人累计兑换过 $100 触发实名升级,环比 +52%", "5/27");
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
