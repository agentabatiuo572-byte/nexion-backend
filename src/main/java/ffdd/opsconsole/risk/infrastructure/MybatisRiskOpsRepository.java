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
import ffdd.opsconsole.risk.domain.RiskWithdrawCandidateView;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    private static final List<RiskArbitrageParamView> RHYTHM_ARBITRAGE_PARAMS = List.of(
            new RiskArbitrageParamView("rewardRisk.lockMode", "新人礼发放模式", "risk_bucket", "配置持久化；待发奖服务消费", "当前不改变实际入账"),
            new RiskArbitrageParamView("rewardRisk.usdtAmount", "新人礼 USDT 金额", "5", "配置持久化；待发奖服务消费", "当前不改变实际入账"),
            new RiskArbitrageParamView("rewardRisk.nexAmount", "新人礼 NEX 金额", "20", "配置持久化；待发奖服务消费", "当前不改变实际入账"),
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
        mapper.createRouteCountTable();
        mapper.createWithdrawHitTable();
        ensureWithdrawHitUniqueKey();
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
    public List<RiskWithdrawCandidateView> withdrawRuleCandidates(int limit) {
        return mapper.withdrawRuleCandidates(Math.max(1, Math.min(limit, 500)));
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
        Map<String, RiskArbitrageParamView> merged = new LinkedHashMap<>();
        RHYTHM_ARBITRAGE_PARAMS.forEach(row -> merged.put(row.key(), row));
        mapper.arbitrageParams().forEach(row -> merged.put(row.key(), row));
        return new ArrayList<>(merged.values());
    }

    @Override
    public Optional<RiskArbitrageParamView> updateArbitrageParam(String key, String value) {
        int updated = mapper.updateArbitrageParam(key, value);
        if (updated == 0) {
            RiskArbitrageParamView definition = RHYTHM_ARBITRAGE_PARAMS.stream()
                    .filter(row -> row.key().equals(key))
                    .findFirst()
                    .orElse(null);
            if (definition == null) return Optional.empty();
            mapper.insertArbitrageParam(key, definition.name(), value, definition.sub(), definition.note());
        }
        return Optional.ofNullable(mapper.findArbitrageParam(key));
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
                values.getOrDefault("inputSource", ""),
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
        if (mapper.updateRiskParam("k5", key, value) == 0) {
            insertK5Param(key, value);
        }
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
        return fallback;
    }

    private void insertK5Param(String key, String value) {
        switch (key) {
            case "reviewTriggerScore" -> mapper.insertRiskParam("k5", key, "风险分复审线", value, null,
                    "K4 有效风险分达到该值时进入 KYC 复审", "基础风控配置；来自 nx_admin_risk_param", 0);
            case "largeWithdrawReviewUsdt" -> mapper.insertRiskParam("k5", key, "大额提现复审线", value, null,
                    "命中 -> 生成复审工单 + 提现单冻结", "范围 $100-$50,000。与 K3/D2 是独立参数", 1);
            case "largeExchangeReviewUsdt" -> mapper.insertRiskParam("k5", key, "大额兑换复审线", value, null,
                    "命中 -> 生成复审工单 + 兑换单人工处理", "范围 $100-$50,000。由 G2 兑换读取", 2);
            default -> {
            }
        }
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

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
