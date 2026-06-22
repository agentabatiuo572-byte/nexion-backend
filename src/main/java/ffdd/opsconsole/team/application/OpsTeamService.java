package ffdd.opsconsole.team.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsTeamService {
    private static final Set<String> ACTIVE_KEYS = Set.of(
            "directRoyaltyPct",
            "networkRoyaltyPct",
            "binaryPairRatePct",
            "maxCombinedOutflowPct",
            "minPayoutUsdt",
            "rankWindowDays",
            "hardwareQuotaPerRank");
    private static final Set<String> UI_CONFIG_KEYS = uiConfigKeys();

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F");
        response.put("commissionPolicy", commissionPolicy());
        response.put("rankLadder", rankLadder());
        response.put("vrankRows", vrankRows());
        response.put("fulfillmentQueues", fulfillmentQueues());
        response.put("unilevelRates", unilevelRates());
        response.put("policyParams", policyParams());
        response.put("rateTiers", rateTiers());
        response.put("binarySettlements", binarySettlements());
        response.put("binaryMaxTrackGmv", 84000);
        response.put("binaryParticipantCount", 1842);
        response.put("binaryMonthlyMatchedUsd", 214800);
        response.put("binaryAutoPlacement7dCount", 1284);
        response.put("binaryDailyCapCurrentLabel", "$5,000");
        response.put("binaryDailyCapWindowLabel", "月 1-6 现值 · 全局统一");
        response.put("binaryDailyCapNextTrigger", "月 7");
        response.put("binaryDailyCapNextLabel", "$2,000");
        response.put("leadershipPool", leadershipPool());
        response.put("quotaPolicy", quotaPolicy());
        response.put("payoutGuardrails", guardrails());
        response.put("configValues", configValues());
        response.put("sunsetExclusions", List.of("Premium rank unlock", "Points commission payout", "NEX v2 lock reward"));
        response.put("sources", List.of(
                "nx_config_item:team.*",
                "nx_team_rank_snapshot",
                "nx_team_binary_settlement_view",
                "nx_team_leadership_pool_view",
                "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> commissions() {
        List<Map<String, Object>> commissionEvents = commissionEvents();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F2_F3");
        response.put("commissionPolicy", commissionPolicy());
        response.put("rateTiers", rateTiers());
        response.put("payoutAssetPolicy", Map.of(
                "primary", "USDT",
                "secondary", "NEX",
                "points", "SUNSET_HISTORY_ONLY"));
        response.put("guardrails", guardrails());
        response.put("configValues", configValues());
        response.put("summary", commissionSummary(commissionEvents));
        response.put("commissionKinds", commissionKinds());
        response.put("commissionFilters", commissionFilters());
        response.put("commissionEvents", commissionEvents);
        response.put("statusDistribution", commissionStatusDistribution(commissionEvents));
        response.put("recentAuditFeed", commissionAuditFeed());
        response.put("pagination", Map.of(
                "mode", "server-pageable",
                "defaultWindow", "24h",
                "defaultPageSize", 20,
                "maxPageSize", 100));
        response.put("sources", List.of(
                "nx_team_commission_event",
                "nx_config_item:team.ui.F.commission.*.status",
                "A2 audit:TEAM_POLICY"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> ranks() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F1");
        response.put("rankLadder", rankLadder());
        response.put("promotionWindowDays", configDecimal("team.rank_window_days", new BigDecimal("30")).intValue());
        response.put("quotaPolicy", quotaPolicy());
        response.put("configValues", configValues());
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateConfig(String idempotencyKey, TeamCommissionConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = requireText(request.key(), "TEAM_CONFIG_KEY_REQUIRED");
        rejectSunsetKey(key);
        if (!ACTIVE_KEYS.contains(key)) {
            return updateUiConfig(idempotencyKey, request, key);
        }
        BigDecimal oldValue = currentValue(key);
        BigDecimal newValue = normalizeValue(key, request.value());
        if (loosensPayoutControl(key, oldValue, newValue) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        String configKey = configKey(key);
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "team", "F domain team commission policy");
        audit("F_TEAM_POLICY_CHANGED", configKey, request.operator(), Map.of(
                "key", key,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = overview().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    private ApiResult<Map<String, Object>> updateUiConfig(
            String idempotencyKey, TeamCommissionConfigUpdateRequest request, String key) {
        if (!UI_CONFIG_KEYS.contains(key)) {
            throw new IllegalArgumentException("Unsupported F team UI config key");
        }
        String value = normalizeUiValue(request.value());
        String configKey = uiConfigKey(key);
        String oldValue = configFacade.activeValue(configKey).orElse("");
        configFacade.upsertAdminValue(configKey, value, "TEXT", "team", "F domain UI-backed policy state");
        audit("F_TEAM_UI_CONFIG_CHANGED", configKey, request.operator(), Map.of(
                "key", key,
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = overview().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "oldValue", oldValue, "newValue", value));
        return ApiResult.ok(response);
    }

    private Map<String, Object> commissionPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("directRoyaltyPct", percent(configDecimal("team.direct_royalty_pct", new BigDecimal("8"))));
        policy.put("networkRoyaltyPct", percent(configDecimal("team.network_royalty_pct", new BigDecimal("2"))));
        policy.put("binaryPairRatePct", percent(configDecimal("team.binary_pair_rate_pct", new BigDecimal("1.5"))));
        policy.put("maxCombinedOutflowPct", percent(configDecimal("team.max_combined_outflow_pct", new BigDecimal("25"))));
        policy.put("minPayoutUsdt", configDecimal("team.min_payout_usdt", new BigDecimal("20")));
        policy.put("settlementStatus", "single-process-transaction");
        return policy;
    }

    private List<Map<String, Object>> rankLadder() {
        int window = configDecimal("team.rank_window_days", new BigDecimal("30")).intValue();
        return List.of(
                rank("V1", "starter", new BigDecimal("0"), new BigDecimal("0"), window),
                rank("V3", "builder", new BigDecimal("5000"), new BigDecimal("3"), window),
                rank("V6", "scale", new BigDecimal("50000"), new BigDecimal("6"), window),
                rank("V9", "partner", new BigDecimal("250000"), new BigDecimal("9"), window));
    }

    private Map<String, Object> rank(String rank, String label, BigDecimal minTeamGmvUsdt, BigDecimal maxDepth, int windowDays) {
        return Map.of(
                "rank", rank,
                "label", label,
                "minTeamGmvUsdt", minTeamGmvUsdt,
                "commissionDepth", maxDepth,
                "windowDays", windowDays);
    }

    private List<Map<String, Object>> vrankRows() {
        return List.of(
                vrank("V0", "—", "—", "—", 84231),
                vrank("V1", "自买 $299 · 直推 3", "Pilot 徽章", "500", 12483),
                vrank("V2", "团队 GV $5k", "操作员勋章", "2,000", 3247),
                vrank("V3", "团队 GV $20k · 2×V1", "Apple Watch SE", "10,000", 487),
                vrank("V4", "团队 GV $50k · 3×V2", "iPhone 16 Pro", "50,000", 102),
                vrank("V5", "团队 GV $150k · 4×V3", "Apple Vision Pro", "200,000", 21),
                vrank("V6", "团队 GV $500k · 5×V4", "Rolex Submariner", "800,000", 3),
                vrank("V7", "团队 GV $1M · 6×V5", "Tesla Model Y", "3,200,000", 1),
                vrank("V8", "团队 GV $3M · 7×V6", "Porsche 911", "10,000,000", 0),
                vrank("V9", "团队 GV $10M", "Lamborghini Urus", "—", 0),
                vrank("V10", "团队 GV $30M", "私人飞机包月", "—", 0),
                vrank("V11", "团队 GV $100M", "加勒比游艇度假", "—", 0),
                vrank("V12", "团队 GV $500M", "上市公司股权", "—", 0));
    }

    private Map<String, Object> vrank(String v, String threshold, String prize, String nexReward, int population) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", v);
        row.put("threshold", configFacade.activeValue(uiConfigKey("F.vrank." + v)).orElse(threshold));
        row.put("defaultThreshold", threshold);
        row.put("prize", prize);
        row.put("nexReward", nexReward);
        row.put("population", population);
        row.put("configKey", "F.vrank." + v);
        return row;
    }

    private List<Map<String, Object>> fulfillmentQueues() {
        return List.of(
                fulfillment("V3", "Apple Watch SE", 24),
                fulfillment("V4", "iPhone 16 Pro", 10),
                fulfillment("V5", "Apple Vision Pro", 3),
                fulfillment("V6", "Rolex Submariner", 1));
    }

    private Map<String, Object> fulfillment(String v, String name, int count) {
        String configKey = "F.fulfillment." + v + ".queue.status";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", v);
        row.put("name", name);
        row.put("count", count);
        row.put("status", configFacade.activeValue(uiConfigKey(configKey)).orElse("pending"));
        row.put("configKey", configKey);
        return row;
    }

    private List<Map<String, Object>> unilevelRates() {
        return List.of(
                unilevel("L1", new BigDecimal("10"), new BigDecimal("50"), "直推 DIRECT", true),
                unilevel("L2", new BigDecimal("5"), new BigDecimal("20"), "扩展 EXTENDED", false),
                unilevel("L3", new BigDecimal("3"), new BigDecimal("10"), "扩展", false),
                unilevel("L4", new BigDecimal("2"), new BigDecimal("5"), "扩展", false),
                unilevel("L5", new BigDecimal("1"), new BigDecimal("2.5"), "扩展", false),
                unilevel("L6", new BigDecimal("0.5"), new BigDecimal("1"), "扩展", false),
                unilevel("L7", new BigDecimal("0.5"), new BigDecimal("1"), "扩展", false));
    }

    private Map<String, Object> unilevel(
            String level, BigDecimal defaultUsdtPct, BigDecimal defaultNexReward, String label, boolean direct) {
        String configKey = "F.unilevel." + level;
        String nexConfigKey = "F.unilevel.nex." + level;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("level", level);
        row.put("usdtPct", configDecimal(uiConfigKey(configKey), defaultUsdtPct));
        row.put("nexReward", configDecimal(uiConfigKey(nexConfigKey), defaultNexReward));
        row.put("label", label);
        row.put("direct", direct);
        row.put("configKey", configKey);
        row.put("nexConfigKey", nexConfigKey);
        return row;
    }

    private List<Map<String, Object>> policyParams() {
        return List.of(
                policyParam("clamp", "影响分上下限", "F.influence.clamp", "1.0 – 5.0", "", "InfluenceScore 上下限;clamp 后参与版税权重计算。", false, false, ""),
                policyParam("cool", "佣金冷却", "F.cooldown", "30d", "", "计提后冷却期;期满才进入可提余额。改后对新计提佣金生效。", false, false, "天"),
                policyParam("promo", "promo 周倍率", "F.promo.weekMultiplier", "1.0×", "warn", "活动周对网络版税的倍率放大。放大佣金流出,受 B1 覆盖率约束。", true, true, "×"),
                policyParam("min", "版税支付阈值", "F.royalty.minPayout", "$10", "", "最小可提金额。调高 = 凑不够提不出。", false, false, ""),
                policyParam("peer", "peer 平级比例", "F.peer.rate", "5%", "brand", "同 V 级平级奖励比例(V3+)。放大佣金流出。", true, true, "%"),
                policyParam("depth", "深度门槛", "F.unilevel.depthGate", "V2+", "cyan", "L4 以下层级需 V2 以上才解锁,防止低层级套利簇。", false, false, ""),
                policyParam("nexcap", "NEX/USDT 折算上限", "F.unilevel.nexCap", "$50/d", "", "单用户单日 NEX 派发折算 USDT 的上限。", false, false, ""),
                policyParam("backfill", "回溯窗口", "F.unilevel.backfill", "0d", "", "改后是否回溯已计提,原则上不回溯。", true, false, "天"),
                policyParam("binary-threshold", "两轨结算门槛", "F.binary.threshold", "$1,000 / 轨", "", "最低门槛;改后对下一周期结算生效。", false, false, ""),
                policyParam("binary-rate", "平衡匹配比例", "F.binary.matchRate", "10%", "brand", "min(A,B) × 该比例日结算;放大佣金流出,受 B1 覆盖率约束。", true, true, "%"),
                policyParam("pool-ratio", "领导池比例", "F.pool.ratio", "5%", "brand", "每周 GMV 注入领导池的比例;放大池子流出,受 B1 约束。", true, true, "%"));
    }

    private Map<String, Object> policyParam(
            String id,
            String name,
            String key,
            String defaultValue,
            String viewClass,
            String sub,
            boolean amplifies,
            boolean visualAmplify,
            String unit) {
        return Map.of(
                "id", id,
                "name", name,
                "key", key,
                "value", configFacade.activeValue(uiConfigKey(key)).orElse(defaultValue),
                "defaultValue", defaultValue,
                "viewClass", viewClass,
                "sub", sub,
                "amplifies", amplifies,
                "visualAmplify", visualAmplify,
                "unit", unit);
    }

    private Map<String, Object> quotaPolicy() {
        return Map.of(
                "hardwareQuotaPerRank", configDecimal("team.hardware_quota_per_rank", new BigDecimal("5")).intValue(),
                "quotaOwner", "F5",
                "deviceReleasePacing", "mirrors H1 but does not own H1 phase dials",
                "b1PrecheckOnLoosening", true);
    }

    private List<Map<String, Object>> rateTiers() {
        return List.of(
                tier("Standard", "$0+ 网络 GMV", configDecimal("team.direct_royalty_pct", new BigDecimal("8")), "62%", "t-0"),
                tier("Verified", "$5,000+ 网络 GMV", configDecimal("team.direct_royalty_pct", new BigDecimal("8")).add(new BigDecimal("2")), "24%", "t-1"),
                tier("Partner", "$50,000+ 网络 GMV", configDecimal("team.direct_royalty_pct", new BigDecimal("8")).add(new BigDecimal("4")), "11%", "t-2"),
                tier("Diamond", "$500,000+ 网络 GMV", configDecimal("team.direct_royalty_pct", new BigDecimal("8")).add(new BigDecimal("7")), "3%", "t-3"));
    }

    private Map<String, Object> tier(String name, String requirement, BigDecimal ratePct, String distribution, String className) {
        return Map.of(
                "name", name,
                "requirement", requirement,
                "ratePct", ratePct,
                "distribution", distribution,
                "className", className);
    }

    private List<Map<String, Object>> binarySettlements() {
        return List.of(
                binarySettlement("usr_31E8", 84000, 62000, 6200, 1500, "结算中", "ok"),
                binarySettlement("usr_19C7", 38000, 41000, 3800, 1500, "达封顶", "warn"),
                binarySettlement("usr_02A9", 12000, 800, 0, 0, "阻塞 · B轨 < $1k", "err"),
                binarySettlement("usr_84F2", 5400, 4900, 490, 490, "结算中", "ok"));
    }

    private Map<String, Object> binarySettlement(
            String user, int trackA, int trackB, int matchAmount, int todayPaid, String state, String tone) {
        return Map.of(
                "user", user,
                "trackA", trackA,
                "trackB", trackB,
                "matchAmount", matchAmount,
                "todayPaid", todayPaid,
                "state", state,
                "tone", tone);
    }

    private Map<String, Object> leadershipPool() {
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("weeklyInjectedUsd", 214000);
        pool.put("weeklyGmvUsd", 4280000);
        pool.put("participantCount", 45);
        pool.put("top10SharePct", 80);
        pool.put("settlementWindow", "Sunday 23:59 UTC");
        pool.put("ambassadorBudgetApprovedLabel", "$48,200");
        pool.put("ambassadorBudgetCapLabel", "$80,000");
        pool.put("ambassadorKolBudgetPct", 50);
        pool.put("ambassadorNextQuotaReviewDate", "2026-09-01");
        pool.put("leaderboardParticipantCount", 1284);
        pool.put("leaderboardFraudHitCount", 3);
        pool.put("quotaRows", quotaRows());
        pool.put("ambassadorBands", ambassadorBands());
        pool.put("podium", leaderboardPodium());
        pool.put("voteWeights", voteWeights());
        return pool;
    }

    private List<Map<String, Object>> quotaRows() {
        return List.of(
                quotaRow("Pro", 48, 70, false),
                quotaRow("Rack", 22, 26, true));
    }

    private Map<String, Object> quotaRow(String name, int current, int cap, boolean tight) {
        return Map.of("name", name, "current", current, "cap", cap, "tight", tight);
    }

    private List<Map<String, Object>> ambassadorBands() {
        return List.of(
                Map.of("name", "KOL", "count", 3),
                Map.of("name", "EVENT", "count", 2),
                Map.of("name", "AD", "count", 1),
                Map.of("name", "LOCAL", "count", 1));
    }

    private List<Map<String, Object>> leaderboardPodium() {
        return List.of(
                podium(2, "usr_19C7", "$182k", "本期 GV", "r-2"),
                podium(1, "usr_31E8", "$214k", "本期 GV", "r-1"),
                podium(3, "usr_55B1", "$156k", "取消资格", "r-3 dq"));
    }

    private Map<String, Object> podium(int rank, String userId, String gmvLabel, String tip, String className) {
        return Map.of(
                "rank", rank,
                "userId", userId,
                "gmvLabel", gmvLabel,
                "tip", tip,
                "className", className);
    }

    private List<Map<String, Object>> voteWeights() {
        return List.of(
                voteWeight("V3", 1),
                voteWeight("V4", 2),
                voteWeight("V5", 4),
                voteWeight("V6", 8),
                voteWeight("V7", 16),
                voteWeight("V8", 32),
                voteWeight("V9", 64),
                voteWeight("V10", 128),
                voteWeight("V11", 256),
                voteWeight("V12", 512));
    }

    private Map<String, Object> voteWeight(String v, int votes) {
        String configKey = "F.pool.votes." + v;
        String effective = configFacade.activeValue(uiConfigKey(configKey)).orElse(String.valueOf(votes));
        return Map.of("v", v, "votes", parseDecimal(effective, BigDecimal.valueOf(votes)), "configKey", configKey);
    }

    private Map<String, Object> commissionSummary(List<Map<String, Object>> commissionEvents) {
        long abnormalOrFrozen = commissionEvents.stream()
                .map(event -> String.valueOf(event.get("state")))
                .filter(state -> "异常回退".equals(state) || "frozen".equals(state) || "rejected".equals(state))
                .count();
        return Map.of(
                "monthlyCommissionSpendLabel", "$8.42M",
                "coolingBalanceLabel", "$2.46M",
                "withdrawableThisMonthLabel", "$5.96M",
                "abnormalOrFrozenCount", abnormalOrFrozen);
    }

    private List<Map<String, Object>> commissionKinds() {
        return List.of(
                commissionKind("all", "ALL", "全部佣金类型", "$8.42M", "12,847 笔", "k-network", ""),
                commissionKind("network", "NETWORK", "L1-L7 网络版税", "$3.21M", "8,124 笔", "k-network", "var(--brand)"),
                commissionKind("binary", "BINARY", "双轨平衡匹配", "$2.18M", "1,842 笔", "k-binary", "var(--cyan)"),
                commissionKind("leadership", "LEADERSHIP", "领导奖池", "$1.46M", "214 笔", "k-leadership", "var(--warning)"),
                commissionKind("cultivation", "CULTIVATION", "培育奖 NEX", "$0.92M", "418 笔", "k-cultivation", "#B6A4FF"),
                commissionKind("genesis", "GENESIS", "创世节点二级版税", "$0.65M", "2,249 笔", "k-genesis", "var(--brand-2)"));
    }

    private Map<String, Object> commissionKind(
            String key, String code, String label, String amountLabel, String countLabel, String className, String amountColor) {
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("key", key);
        kind.put("code", code);
        kind.put("label", label);
        kind.put("amountLabel", amountLabel);
        kind.put("countLabel", countLabel);
        kind.put("className", className);
        kind.put("amountColor", amountColor);
        return kind;
    }

    private List<Map<String, Object>> commissionFilters() {
        return List.of(
                Map.of("key", "all", "label", "全部状态"),
                Map.of("key", "计提", "label", "计提中"),
                Map.of("key", "可提", "label", "已解锁可提"),
                Map.of("key", "frozen", "label", "已冻结"),
                Map.of("key", "rejected", "label", "已驳回"),
                Map.of("key", "异常回退", "label", "异常回退"));
    }

    private List<Map<String, Object>> commissionEvents() {
        return List.of(
                commissionEvent("CM-7781", "network", "usr_19C7", new BigDecimal("420"), "USDT", 60, "冷却 18d", "计提"),
                commissionEvent("CM-7780", "binary", "usr_31E8", new BigDecimal("1500"), "USDT", 100, "已解锁", "可提"),
                commissionEvent("CM-7779", "cultivation", "usr_02A9", new BigDecimal("200"), "NEX", 0, "冷却 30d", "计提"),
                commissionEvent("CM-7778", "leadership", "usr_77D4", new BigDecimal("1240"), "USDT", 100, "已解锁", "可提"),
                commissionEvent("CM-7777", "leadership", "usr_31E8", new BigDecimal("880"), "USDT", 100, "已解锁", "可提"),
                commissionEvent("CM-7776", "network", "usr_84F2", new BigDecimal("65"), "USDT", 42, "冷却 17d", "计提"),
                commissionEvent("CM-7775", "binary", "usr_84F2", new BigDecimal("490"), "USDT", 100, "已解锁", "可提"),
                commissionEvent("CM-7774", "genesis", "usr_19C7", new BigDecimal("90"), "USDT", 100, "已解锁", "可提"),
                commissionEvent("CM-7773", "cultivation", "usr_55B1", new BigDecimal("3500"), "NEX", 25, "冷却 22d", "计提"),
                commissionEvent("CM-7772", "leadership", "usr_19C7", new BigDecimal("520"), "USDT", 0, "冻结", "frozen"),
                commissionEvent("CM-7771", "network", "usr_02A9", new BigDecimal("140"), "USDT", 0, "已驳回", "rejected"),
                commissionEvent("CM-7770", "network", "usr_55B1", new BigDecimal("140"), "USDT", 0, "撤销", "异常回退"));
    }

    private Map<String, Object> commissionEvent(
            String id,
            String kind,
            String user,
            BigDecimal amount,
            String currency,
            int cooldownPercent,
            String cooldownLabel,
            String fallbackState) {
        String state = configFacade.activeValue(uiConfigKey("F.commission." + id + ".status"))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .orElse(fallbackState);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("kind", kind);
        event.put("user", user);
        event.put("amount", amount);
        event.put("currency", currency);
        event.put("cooldownPercent", cooldownPercent);
        event.put("cooldownLabel", "unlocked".equals(state) ? "已解锁(运营)" : cooldownLabel);
        event.put("state", state);
        event.put("auditKey", "F.commission." + id + ".status");
        return event;
    }

    private List<Map<String, Object>> commissionStatusDistribution(List<Map<String, Object>> commissionEvents) {
        return List.of(
                statusItem("var(--success)", "已解锁可提", countStates(commissionEvents, "可提", "unlocked")),
                statusItem("var(--warning)", "冷却计提中", countStates(commissionEvents, "计提")),
                statusItem("var(--danger)", "异常回退 · 红冲", countStates(commissionEvents, "异常回退")),
                statusItem("var(--ink-4)", "已驳回 / 已冻结", countStates(commissionEvents, "rejected", "frozen")));
    }

    private Map<String, Object> statusItem(String color, String name, long count) {
        return Map.of("color", color, "name", name, "count", count);
    }

    private long countStates(List<Map<String, Object>> events, String... states) {
        Set<String> expected = Set.of(states);
        return events.stream()
                .map(event -> String.valueOf(event.get("state")))
                .filter(expected::contains)
                .count();
    }

    private List<Map<String, Object>> commissionAuditFeed() {
        return List.of(
                Map.of("when", "2m", "text", "CM-7770 网络版税异常回退已驳回,红冲 D4,risk-ops", "level", "HIGH"),
                Map.of("when", "14m", "text", "CM-7762 培育奖 NEX 已冻结,K2 套利簇,risk-ops", "level", "HIGH"),
                Map.of("when", "38m", "text", "CM-7747 领导奖提前解锁,周结算优先,super-admin", "level", "MEDIUM"),
                Map.of("when", "1h", "text", "批量解锁 89 笔,双轨佣金冷却到期,server cron", "level", "LOW"),
                Map.of("when", "3h", "text", "CM-7710 培育奖已驳回,红冲,risk-ops", "level", "HIGH"));
    }

    private List<String> guardrails() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return List.of(
                "B1 coverageRatio=" + coverage.coverageRatio() + ", redline=" + coverage.redlinePct(),
                "payout-loosening writes require Idempotency-Key and confirm-with-reason",
                "team commission can request D ledger posting but cannot write wallet mapper directly");
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private void rejectSunsetKey(String key) {
        String normalized = requireText(key, "TEAM_CONFIG_KEY_REQUIRED");
        String lower = normalized.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (lower.contains("premium") || lower.contains("points") || lower.contains("nexv2") || lower.contains("nex.v2")) {
            throw new IllegalArgumentException("Sunset capability is not an active F domain key");
        }
    }

    private BigDecimal currentValue(String key) {
        return switch (key) {
            case "directRoyaltyPct" -> configDecimal("team.direct_royalty_pct", new BigDecimal("8"));
            case "networkRoyaltyPct" -> configDecimal("team.network_royalty_pct", new BigDecimal("2"));
            case "binaryPairRatePct" -> configDecimal("team.binary_pair_rate_pct", new BigDecimal("1.5"));
            case "maxCombinedOutflowPct" -> configDecimal("team.max_combined_outflow_pct", new BigDecimal("25"));
            case "minPayoutUsdt" -> configDecimal("team.min_payout_usdt", new BigDecimal("20"));
            case "rankWindowDays" -> configDecimal("team.rank_window_days", new BigDecimal("30"));
            case "hardwareQuotaPerRank" -> configDecimal("team.hardware_quota_per_rank", new BigDecimal("5"));
            default -> throw new IllegalArgumentException("Unsupported F team policy key");
        };
    }

    private String configKey(String key) {
        return switch (key) {
            case "directRoyaltyPct" -> "team.direct_royalty_pct";
            case "networkRoyaltyPct" -> "team.network_royalty_pct";
            case "binaryPairRatePct" -> "team.binary_pair_rate_pct";
            case "maxCombinedOutflowPct" -> "team.max_combined_outflow_pct";
            case "minPayoutUsdt" -> "team.min_payout_usdt";
            case "rankWindowDays" -> "team.rank_window_days";
            case "hardwareQuotaPerRank" -> "team.hardware_quota_per_rank";
            default -> throw new IllegalArgumentException("Unsupported F team policy key");
        };
    }

    private BigDecimal normalizeValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "directRoyaltyPct", "networkRoyaltyPct", "binaryPairRatePct" -> bounded(value, BigDecimal.ZERO, new BigDecimal("30"));
            case "maxCombinedOutflowPct" -> bounded(value, BigDecimal.ZERO, new BigDecimal("60"));
            case "minPayoutUsdt" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000"));
            case "rankWindowDays" -> whole(value, 1, 365);
            case "hardwareQuotaPerRank" -> whole(value, 0, 100000);
            default -> throw new IllegalArgumentException("Unsupported F team policy key");
        };
    }

    private String normalizeUiValue(String raw) {
        String value = requireText(raw, "TEAM_CONFIG_VALUE_REQUIRED");
        if (value.length() > 160) {
            throw new IllegalArgumentException("F team UI config value is too long");
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            throw new IllegalArgumentException("F team UI config does not accept raw JSON");
        }
        return trimmed;
    }

    private Map<String, String> configValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : UI_CONFIG_KEYS) {
            configFacade.activeValue(uiConfigKey(key)).ifPresent(value -> values.put(key, value));
        }
        return values;
    }

    private String uiConfigKey(String key) {
        return "team.ui." + key;
    }

    private static Set<String> uiConfigKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i <= 12; i++) {
            keys.add("F.vrank.V" + i);
        }
        for (int i = 1; i <= 12; i++) {
            keys.add("F.fulfillment.V" + i + ".queue.status");
        }
        for (int i = 1; i <= 7; i++) {
            keys.add("F.unilevel.L" + i);
            keys.add("F.unilevel.nex.L" + i);
        }
        keys.addAll(List.of(
                "F.influence.clamp",
                "F.cooldown",
                "F.promo.weekMultiplier",
                "F.royalty.minPayout",
                "F.peer.rate",
                "F.unilevel.depthGate",
                "F.unilevel.nexCap",
                "F.unilevel.backfill",
                "F.binary.threshold",
                "F.binary.matchRate",
                "F.binary.spillover",
                "F.binary.gvResetCron",
                "F.pool.ratio",
                "F.pool.monthlyCap",
                "F.quota.proUnlock",
                "F.quota.rackUnlock",
                "F.quota.monthlyStock",
                "F.ambassador.q3-2025.status",
                "F.leaderboard.poolUsd",
                "F.leaderboard.period.status"));
        for (int i = 3; i <= 12; i++) {
            keys.add("F.pool.votes.V" + i);
        }
        for (int i = 7770; i <= 7781; i++) {
            keys.add("F.commission.CM-" + i + ".status");
        }
        return Collections.unmodifiableSet(keys);
    }

    private boolean loosensPayoutControl(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "directRoyaltyPct", "networkRoyaltyPct", "binaryPairRatePct", "maxCombinedOutflowPct", "hardwareQuotaPerRank" ->
                    newValue.compareTo(oldValue) > 0;
            case "minPayoutUsdt", "rankWindowDays" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .flatMap(value -> Optional.ofNullable(parseDecimal(value, fallback)))
                .orElse(fallback);
    }

    private BigDecimal parseDecimal(String raw) {
        return parseDecimal(raw, null);
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Numeric value is required");
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(",", ""));
        } catch (NumberFormatException ex) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Numeric value is invalid", ex);
        }
    }

    private BigDecimal bounded(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("Numeric value is out of range");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal whole(BigDecimal value, int min, int max) {
        int whole = value.setScale(0, RoundingMode.DOWN).intValue();
        if (whole < min || whole > max) {
            throw new IllegalArgumentException("Whole number is out of range");
        }
        return BigDecimal.valueOf(whole);
    }

    private BigDecimal percent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void audit(String action, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("TEAM_POLICY")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}
