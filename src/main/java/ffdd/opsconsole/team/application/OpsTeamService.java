package ffdd.opsconsole.team.application;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.team.dto.VRankRewardRequest;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsTeamService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> REWARD_LIST_TYPE = new TypeReference<>() {
    };
    private static final AtomicLong REWARD_SEQUENCE = new AtomicLong();
    private static final Set<String> ACTIVE_KEYS = Set.of(
            "directRoyaltyPct",
            "networkRoyaltyPct",
            "binaryPairRatePct",
            "maxCombinedOutflowPct",
            "minPayoutUsdt",
            "rankWindowDays",
            "hardwareQuotaPerRank");
    private static final List<String> VRANK_LEVELS = List.of(
            "V0", "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10", "V11", "V12");
    private static final List<VRankSeed> VRANK_SEEDS = List.of(
            new VRankSeed("V0", null, null, null, null, null, 84231),
            new VRankSeed("V1", "$299", "3", null, null, null, 12483),
            new VRankSeed("V2", null, null, "$5k", null, null, 3247),
            new VRankSeed("V3", null, null, "$20k", "2", "V1", 360),
            new VRankSeed("V4", null, null, "$50k", "3", "V2", 84),
            new VRankSeed("V5", null, null, "$150k", "4", "V3", 27),
            new VRankSeed("V6", null, null, "$500k", "5", "V4", 11),
            new VRankSeed("V7", null, null, "$1M", "6", "V5", 6),
            new VRankSeed("V8", null, null, "$3M", "7", "V6", 4),
            new VRankSeed("V9", null, null, "$10M", null, null, 2),
            new VRankSeed("V10", null, null, "$30M", null, null, 2),
            new VRankSeed("V11", null, null, "$100M", null, null, 1),
            new VRankSeed("V12", null, null, "$500M", null, null, 1));
    private static final Map<String, List<Map<String, Object>>> VRANK_REWARD_SEEDS = vrankRewardSeeds();
    private static final Set<String> VRANK_REWARD_TYPES = Set.of("usdt", "nex", "voucher", "sku", "custom");
    private static final List<F2MetricSeed> F2_METRIC_SEEDS = List.of(
            new F2MetricSeed("l1TriggerRate", "L1 触发率", "76%", "目标 80% · 直推转化", ""),
            new F2MetricSeed("maxCombinedOutflow", "合并出口最大", "25%", "Royalty 8-15% + L1 10%", "warn"),
            new F2MetricSeed("weeklyUsdtRoyalty", "本周 USDT 版税", "$182k", "L1-L7 总额", "ok"),
            new F2MetricSeed("weeklyNexPayout", "本周 NEX 派发", "3.46M", "折 ≈ $148k · 受 B1 约束", "cyan"));
    private static final List<F2UnilevelSeed> F2_UNILEVEL_SEEDS = List.of(
            new F2UnilevelSeed("L1", new BigDecimal("10"), new BigDecimal("50"), "直推 DIRECT", true),
            new F2UnilevelSeed("L2", new BigDecimal("5"), new BigDecimal("20"), "扩展 EXTENDED", false),
            new F2UnilevelSeed("L3", new BigDecimal("3"), new BigDecimal("10"), "扩展", false),
            new F2UnilevelSeed("L4", new BigDecimal("2"), new BigDecimal("5"), "扩展", false),
            new F2UnilevelSeed("L5", new BigDecimal("1"), new BigDecimal("2.5"), "扩展", false),
            new F2UnilevelSeed("L6", new BigDecimal("0.5"), new BigDecimal("1"), "扩展", false),
            new F2UnilevelSeed("L7", new BigDecimal("0.5"), new BigDecimal("1"), "扩展", false));
    private static final List<F2RateTierSeed> F2_RATE_TIER_SEEDS = List.of(
            new F2RateTierSeed("Standard", "$0+ 网络 GMV", "8%", "62%", "t-0"),
            new F2RateTierSeed("Verified", "$5,000+ 网络 GMV", "10%", "24%", "t-1"),
            new F2RateTierSeed("Elite", "$50,000+ 网络 GMV", "12%", "11%", "t-2"),
            new F2RateTierSeed("Diamond", "$500,000+ 网络 GMV", "15%", "3%", "t-3"));
    private static final List<F2PolicyParamSeed> F2_POLICY_PARAM_SEEDS = List.of(
            new F2PolicyParamSeed("clampMin", "影响分下限", "F.influence.clampMin", "1.0", "", "InfluenceScore 下限;clamp 后参与版税权重计算。", false, false, ""),
            new F2PolicyParamSeed("clampMax", "影响分上限", "F.influence.clampMax", "5.0", "", "InfluenceScore 上限;clamp 后参与版税权重计算。", false, false, ""),
            new F2PolicyParamSeed("cool", "佣金冷却", "F.cooldown", "30d", "", "计提后冷却期;期满才进入可提余额。改后对新计提佣金生效。", false, false, "天"),
            new F2PolicyParamSeed("promo", "promo 周倍率", "F.promo.weekMultiplier", "1.0×", "warn", "活动周对网络版税的倍率放大。放大佣金流出,受 B1 覆盖率约束。", true, true, "×"),
            new F2PolicyParamSeed("min", "版税支付阈值", "F.royalty.minPayout", "$10", "", "最小可提金额。调高 = 凑不够提不出。", false, false, ""),
            new F2PolicyParamSeed("peer", "peer 平级比例", "F.peer.rate", "5%", "brand", "同 V 级平级奖励比例(V3+)。放大佣金流出。", true, true, "%"),
            new F2PolicyParamSeed("depth", "深度门槛", "F.unilevel.depthGate", "V2+", "cyan", "L4 以下层级需 V2 以上才解锁,防止低层级套利簇。", false, false, ""),
            new F2PolicyParamSeed("nexcap", "NEX/USDT 折算上限", "F.unilevel.nexCap", "$50/d", "", "单用户单日 NEX 派发折算 USDT 的上限。", false, false, ""),
            new F2PolicyParamSeed("backfill", "回溯窗口", "F.unilevel.backfill", "0d", "", "改后是否回溯已计提,原则上不回溯。", true, false, "天"));
    private static final List<F2PolicyParamSeed> F_SHARED_POLICY_PARAM_SEEDS = List.of(
            new F2PolicyParamSeed("binary-threshold", "两轨结算门槛", "F.binary.threshold", "$1,000 / 轨", "", "最低门槛;改后对下一周期结算生效。", false, false, ""),
            new F2PolicyParamSeed("binary-rate", "平衡匹配比例", "F.binary.matchRate", "10%", "brand", "min(A,B) × 该比例日结算;放大佣金流出,受 B1 覆盖率约束。", true, true, "%"),
            new F2PolicyParamSeed("pool-ratio", "领导池比例", "F.pool.ratio", "5%", "brand", "每周 GMV 注入领导池的比例;放大池子流出,受 B1 约束。", true, true, "%"));
    private static final List<F3MetricSeed> F3_METRIC_SEEDS = List.of(
            new F3MetricSeed("todayBalanceMatch", "今日 Balance Match", "$10,490", "4 用户参与结算", "ok"),
            new F3MetricSeed("participantCount", "参与结算用户", "1,842", "两轨均 ≥ $1k 阈值", ""),
            new F3MetricSeed("blockedUsers", "阻塞用户(轨不平衡)", "468", "单轨 < $1k 门槛", "warn"),
            new F3MetricSeed("residualPool", "沉淀池(未匹配)", "$1.2M", "月底归零 · 不结转", "cyan"));
    private static final List<F3BinarySettlementSeed> F3_BINARY_SEEDS = binarySettlementSeeds();
    private static final List<F4QuotaSeed> F4_QUOTA_SEEDS = List.of(
            new F4QuotaSeed("Pro", 48, 70, false),
            new F4QuotaSeed("Rack", 22, 26, true));
    private static final List<F4AmbassadorBandSeed> F4_AMBASSADOR_BAND_SEEDS = List.of(
            new F4AmbassadorBandSeed("KOL", 3),
            new F4AmbassadorBandSeed("EVENT", 2),
            new F4AmbassadorBandSeed("AD", 1),
            new F4AmbassadorBandSeed("LOCAL", 1));
    private static final List<F4PodiumSeed> F4_PODIUM_SEEDS = List.of(
            new F4PodiumSeed(2, "usr_19C7", "$182k", "本期 GV", "r-2"),
            new F4PodiumSeed(1, "usr_31E8", "$214k", "本期 GV", "r-1"),
            new F4PodiumSeed(3, "usr_55B1", "$156k", "取消资格", "r-3 dq"));
    private static final List<Map<String, Object>> F5_COMMISSION_KIND_SEEDS = f5CommissionKinds();
    private static final List<Map<String, Object>> F5_COMMISSION_FILTER_SEEDS = f5CommissionFilters();
    private static final List<Map<String, Object>> F5_COMMISSION_EVENT_SEEDS = f5CommissionEvents();
    private static final List<Map<String, Object>> F5_COMMISSION_AUDIT_FEED_SEEDS = f5CommissionAuditFeed();
    private static final Set<String> UI_CONFIG_KEYS = uiConfigKeys();

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> overview() {
        seedVRankIfMissing();
        seedF2RatesIfMissing();
        seedF3BinaryIfMissing();
        seedF4LeadershipPoolIfMissing();
        seedF5CommissionAuditIfMissing();
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
        response.put("binaryMaxTrackGmv", binaryMaxTrackGmv());
        response.put("binaryParticipantCount", intConfig(uiConfigKey("F.binary.participantCount"), 1842));
        response.put("binaryMonthlyMatchedUsd", intConfig(uiConfigKey("F.binary.monthlyMatchedUsd"), 214800));
        response.put("binaryAutoPlacement7dCount", intConfig(uiConfigKey("F.binary.autoPlacement7dCount"), 1284));
        Map<String, Object> dailyCap = f3DailyCap();
        response.put("binaryDailyCapCurrentLabel", dailyCap.get("currentLabel"));
        response.put("binaryDailyCapWindowLabel", dailyCap.get("windowLabel"));
        response.put("binaryDailyCapNextTrigger", dailyCap.get("nextTrigger"));
        response.put("binaryDailyCapNextLabel", dailyCap.get("nextLabel"));
        response.put("leadershipPool", leadershipPoolReadModel());
        response.put("quotaPolicy", quotaPolicy());
        response.put("payoutGuardrails", guardrails());
        response.put("configValues", configValues());
        response.put("sunsetExclusions", List.of("Premium rank unlock", "Points commission payout", "NEX v2 lock reward"));
        response.put("sources", List.of(
                "nx_config_item:team.*",
                "nx_team_rank_snapshot",
                "nx_team_binary_settlement_view",
                "nx_team_leadership_pool_view",
                "B1 treasury coverage facade",
                "H1 growth rhythm facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> leadershipPool() {
        seedVRankIfMissing();
        seedF4LeadershipPoolIfMissing();
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> pool = leadershipPoolReadModel();
        response.put("domain", "F4");
        response.put("metrics", f4Metrics(pool));
        response.putAll(pool);
        response.put("config", f4Config(pool));
        response.put("commissionPolicy", commissionPolicy());
        response.put("guardrails", guardrails());
        response.put("configValues", configValues());
        response.put("sources", List.of(
                "nx_config_item:team.ui.F4.*",
                "nx_config_item:team.ui.F.pool.*",
                "nx_config_item:team.ui.F.quota.*",
                "nx_config_item:team.ui.F.ambassador.*",
                "nx_config_item:team.ui.F.leaderboard.*",
                "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> commissions() {
        seedF2RatesIfMissing();
        seedF5CommissionAuditIfMissing();
        List<Map<String, Object>> commissionEvents = commissionEvents();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F5");
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
                "nx_config_item:team.ui.F5.commission.*",
                "nx_config_item:team.ui.F.commission.*.status",
                "nx_config_item:team.ui.F5.commission.auditFeed"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> ranks() {
        seedVRankIfMissing();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F1");
        response.put("rankLadder", rankLadder());
        response.put("vrankRows", vrankRows());
        response.put("rewards", vRankRewards());
        response.put("voucherOptions", voucherOptions());
        response.put("voucherLabels", labelMap(voucherOptions()));
        response.put("skuOptions", skuOptions());
        response.put("skuLabels", labelMap(skuOptions()));
        response.put("leadership", leadershipSnapshot());
        response.put("promotionWindowDays", configDecimal("team.rank_window_days", new BigDecimal("30")).intValue());
        response.put("quotaPolicy", quotaPolicy());
        response.put("configValues", configValues());
        response.put("sources", List.of(
                "nx_config_item:team.ui.F.vrank.*",
                "nx_config_item:team.ui.F.vrank.rewards.*",
                "nx_config_item:team.ui.F.pool.*"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> rates() {
        seedF2RatesIfMissing();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F2");
        response.put("metrics", f2Metrics());
        response.put("unilevelRates", unilevelRates());
        response.put("rateTiers", rateTiers());
        response.put("policyParams", f2PolicyParams());
        response.put("commissionPolicy", commissionPolicy());
        response.put("guardrails", guardrails());
        response.put("configValues", configValues());
        response.put("sources", List.of(
                "nx_config_item:team.ui.F.unilevel.*",
                "nx_config_item:team.ui.F.rateTier.*",
                "nx_config_item:team.ui.F.influence.*",
                "nx_config_item:team.ui.F2.metric.*",
                "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> binary() {
        seedF3BinaryIfMissing();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F3");
        response.put("metrics", f3Metrics());
        response.put("formula", f3Formula());
        response.put("settlements", binarySettlements());
        response.put("maxTrackGmv", binaryMaxTrackGmv());
        response.put("participantCount", intConfig(uiConfigKey("F.binary.participantCount"), 1842));
        response.put("blockedCount", intConfig(uiConfigKey("F.binary.blockedCount"), 468));
        response.put("monthlyMatchedUsd", intConfig(uiConfigKey("F.binary.monthlyMatchedUsd"), 214800));
        response.put("autoPlacement7dCount", intConfig(uiConfigKey("F.binary.autoPlacement7dCount"), 1284));
        response.put("dailyMatchUsd", intConfig(uiConfigKey("F.binary.dailyMatchUsd"), 10490));
        response.put("dailyCap", f3DailyCap());
        response.put("config", f3Config());
        response.put("commissionPolicy", commissionPolicy());
        response.put("guardrails", guardrails());
        response.put("configValues", configValues());
        response.put("sources", List.of(
                "nx_config_item:team.ui.F3.binary.rows",
                "nx_config_item:team.ui.F3.metric.*",
                "nx_config_item:team.ui.F.binary.*",
                "B1 treasury coverage facade",
                "H1 growth rhythm facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateVRankThreshold(
            String rank, String field, String idempotencyKey, TeamCommissionConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        seedVRankIfMissing();
        VRankSeed seed = requireVRank(rank);
        String normalizedField = requireText(field, "TEAM_VRANK_FIELD_REQUIRED");
        if (!thresholdFields(seed).contains(normalizedField)) {
            throw new IllegalArgumentException("Unsupported F1 V-Rank threshold field");
        }
        String value = normalizeVRankThreshold(normalizedField, request.value());
        String key = "F.vrank." + seed.v() + "." + normalizedField;
        String configKey = uiConfigKey(key);
        String oldValue = configFacade.activeValue(configKey).orElse(defaultField(seed, normalizedField));
        configFacade.upsertAdminValue(configKey, value, "TEXT", "team", "F1 V-Rank threshold");
        audit("F_TEAM_VRANK_THRESHOLD_CHANGED", configKey, request.operator(), Map.of(
                "rank", seed.v(),
                "field", normalizedField,
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = ranks().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "oldValue", oldValue, "newValue", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> addVRankReward(String rank, String idempotencyKey, VRankRewardRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        seedVRankIfMissing();
        VRankSeed seed = requireVRank(rank);
        List<Map<String, Object>> rewards = new ArrayList<>(activeRewards(seed.v()));
        Map<String, Object> item = normalizeReward(nextRewardId(seed.v()), request);
        rewards.add(item);
        persistRewards(seed.v(), rewards);
        audit("F_TEAM_VRANK_REWARD_ADDED", rewardConfigKey(seed.v()), request.operator(), Map.of(
                "rank", seed.v(),
                "reward", item,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = ranks().getData();
        response.put("updated", Map.of("rank", seed.v(), "reward", item));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateVRankReward(
            String rank, String rewardId, String idempotencyKey, VRankRewardRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        seedVRankIfMissing();
        VRankSeed seed = requireVRank(rank);
        String id = requireText(rewardId, "TEAM_VRANK_REWARD_ID_REQUIRED");
        List<Map<String, Object>> rewards = new ArrayList<>(activeRewards(seed.v()));
        boolean updated = false;
        for (int i = 0; i < rewards.size(); i++) {
            if (id.equals(String.valueOf(rewards.get(i).get("id")))) {
                rewards.set(i, normalizeReward(id, request));
                updated = true;
                break;
            }
        }
        if (!updated) {
            throw new IllegalArgumentException("F1 V-Rank reward not found");
        }
        persistRewards(seed.v(), rewards);
        audit("F_TEAM_VRANK_REWARD_CHANGED", rewardConfigKey(seed.v()), request.operator(), Map.of(
                "rank", seed.v(),
                "rewardId", id,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = ranks().getData();
        response.put("updated", Map.of("rank", seed.v(), "rewardId", id));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> removeVRankReward(
            String rank, String rewardId, String idempotencyKey, VRankRewardRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        seedVRankIfMissing();
        VRankSeed seed = requireVRank(rank);
        String id = requireText(rewardId, "TEAM_VRANK_REWARD_ID_REQUIRED");
        List<Map<String, Object>> rewards = new ArrayList<>(activeRewards(seed.v()));
        boolean removed = rewards.removeIf(item -> id.equals(String.valueOf(item.get("id"))));
        if (!removed) {
            throw new IllegalArgumentException("F1 V-Rank reward not found");
        }
        persistRewards(seed.v(), rewards);
        audit("F_TEAM_VRANK_REWARD_REMOVED", rewardConfigKey(seed.v()), request.operator(), Map.of(
                "rank", seed.v(),
                "rewardId", id,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = ranks().getData();
        response.put("updated", Map.of("rank", seed.v(), "rewardId", id));
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
        return VRANK_SEEDS.stream().map(this::vrank).toList();
    }

    private Map<String, Object> vrank(VRankSeed seed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", seed.v());
        putConfiguredField(row, seed, "selfBuy", seed.selfBuy());
        putConfiguredField(row, seed, "directRefs", seed.directRefs());
        putConfiguredField(row, seed, "teamGv", seed.teamGv());
        putConfiguredField(row, seed, "legCount", seed.legCount());
        putConfiguredField(row, seed, "legRank", seed.legRank());
        row.put("pop", intConfig(uiConfigKey("F.vrank." + seed.v() + ".pop"), seed.pop()));
        row.put("rewards", activeRewards(seed.v()));
        row.put("configKey", "F.vrank." + seed.v());
        return row;
    }

    private void putConfiguredField(Map<String, Object> row, VRankSeed seed, String field, String fallback) {
        if (fallback == null) {
            return;
        }
        row.put(field, configFacade.activeValue(uiConfigKey("F.vrank." + seed.v() + "." + field)).orElse(fallback));
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

    private List<Map<String, Object>> f2Metrics() {
        return F2_METRIC_SEEDS.stream().map(this::f2Metric).toList();
    }

    private Map<String, Object> f2Metric(F2MetricSeed seed) {
        String prefix = "F2.metric." + seed.id();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", seed.id());
        row.put("label", configText(prefix + ".label", seed.label()));
        row.put("value", configText(prefix + ".value", seed.value()));
        row.put("sub", configText(prefix + ".sub", seed.sub()));
        row.put("tone", configText(prefix + ".tone", seed.tone()));
        row.put("configKey", prefix + ".value");
        return row;
    }

    private List<Map<String, Object>> unilevelRates() {
        return F2_UNILEVEL_SEEDS.stream().map(this::unilevel).toList();
    }

    private Map<String, Object> unilevel(F2UnilevelSeed seed) {
        String configKey = "F.unilevel." + seed.level();
        String nexConfigKey = "F.unilevel.nex." + seed.level();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("level", seed.level());
        row.put("usdtPct", configDecimal(uiConfigKey(configKey), seed.usdtPct()));
        row.put("nexReward", configDecimal(uiConfigKey(nexConfigKey), seed.nexReward()));
        row.put("label", configText(configKey + ".label", seed.label()));
        row.put("direct", boolConfig(uiConfigKey(configKey + ".direct"), seed.direct()));
        row.put("configKey", configKey);
        row.put("nexConfigKey", nexConfigKey);
        return row;
    }

    private List<Map<String, Object>> policyParams() {
        List<Map<String, Object>> params = new ArrayList<>();
        F2_POLICY_PARAM_SEEDS.stream().map(this::policyParam).forEach(params::add);
        F_SHARED_POLICY_PARAM_SEEDS.stream().map(this::policyParam).forEach(params::add);
        return params;
    }

    private List<Map<String, Object>> f2PolicyParams() {
        return F2_POLICY_PARAM_SEEDS.stream().map(this::policyParam).toList();
    }

    private Map<String, Object> policyParam(F2PolicyParamSeed seed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", seed.id());
        row.put("name", seed.name());
        row.put("key", seed.key());
        row.put("value", configFacade.activeValue(uiConfigKey(seed.key())).orElse(seed.defaultValue()));
        row.put("defaultValue", seed.defaultValue());
        row.put("viewClass", seed.viewClass());
        row.put("sub", seed.sub());
        row.put("amplifies", seed.amplifies());
        row.put("visualAmplify", seed.visualAmplify());
        row.put("unit", seed.unit());
        return row;
    }

    private Map<String, Object> quotaPolicy() {
        return Map.of(
                "hardwareQuotaPerRank", configDecimal("team.hardware_quota_per_rank", new BigDecimal("5")).intValue(),
                "quotaOwner", "F5",
                "deviceReleasePacing", "mirrors H1 but does not own H1 phase dials",
                "b1PrecheckOnLoosening", true);
    }

    private List<Map<String, Object>> rateTiers() {
        return F2_RATE_TIER_SEEDS.stream().map(this::tier).toList();
    }

    private Map<String, Object> tier(F2RateTierSeed seed) {
        String prefix = "F.rateTier." + seed.name();
        String rate = configText(prefix + ".rate", seed.rate());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", configText(prefix + ".name", seed.name()));
        row.put("requirement", configText(prefix + ".requirement", seed.requirement()));
        row.put("rate", rate);
        row.put("ratePct", parseDecimal(rate, parseDecimal(seed.rate(), BigDecimal.ZERO)).stripTrailingZeros());
        row.put("distribution", configText(prefix + ".distribution", seed.distribution()));
        row.put("className", configText(prefix + ".className", seed.className()));
        row.put("configKey", prefix);
        return row;
    }

    private List<Map<String, Object>> f3Metrics() {
        return F3_METRIC_SEEDS.stream().map(this::f3Metric).toList();
    }

    private Map<String, Object> f3Metric(F3MetricSeed seed) {
        String prefix = "F3.metric." + seed.id();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", seed.id());
        row.put("label", configText(prefix + ".label", seed.label()));
        row.put("value", configText(prefix + ".value", seed.value()));
        row.put("sub", configText(prefix + ".sub", seed.sub()));
        row.put("tone", configText(prefix + ".tone", seed.tone()));
        row.put("configKey", prefix + ".value");
        return row;
    }

    private Map<String, Object> f3Formula() {
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("user", configText("F3.formula.user", "usr_31E8"));
        formula.put("trackA", intConfig(uiConfigKey("F3.formula.trackA"), 84000));
        formula.put("trackB", intConfig(uiConfigKey("F3.formula.trackB"), 62000));
        formula.put("matchAmount", intConfig(uiConfigKey("F3.formula.matchAmount"), 6200));
        formula.put("matchRate", configText("F.binary.matchRate", "10%"));
        formula.put("threshold", configText("F.binary.threshold", "$1,000 / 轨"));
        formula.put("settlePeriod", configText("F.binary.settlePeriod", "每月"));
        return formula;
    }

    private Map<String, Object> f3Config() {
        String spillover = configText("F.binary.spillover", "已启用");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("threshold", configText("F.binary.threshold", "$1,000 / 轨"));
        config.put("matchRate", configText("F.binary.matchRate", "10%"));
        config.put("spillover", spillover);
        config.put("spilloverEnabled", !"已关闭".equals(spillover));
        config.put("gvResetCron", configText("F.binary.gvResetCron", "每月 1 日 00:00 UTC"));
        config.put("settlePeriod", configText("F.binary.settlePeriod", "每月"));
        config.put("residualPolicy", configText("F.binary.residualPolicy", "每月清零"));
        config.put("residualPool", configText("F.binary.residualPool", "$1.2M"));
        config.put("residualSub", configText("F.binary.residualSub", "月底归零 · 不结转"));
        return config;
    }

    private Map<String, Object> f3DailyCap() {
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade);
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("currentLabel", configText("F3.dailyCap.currentLabel", "$5,000"));
        cap.put("windowLabel", configText("F3.dailyCap.windowLabel", "月 1-6 现值 · 全局统一"));
        cap.put("nextTrigger", configText("F3.dailyCap.nextTrigger", "月 7"));
        cap.put("nextLabel", configText("F3.dailyCap.nextLabel", "$2,000"));
        cap.put("currentMonth", rhythm.currentMonth());
        cap.put("currentPhase", rhythm.currentPhase());
        cap.put("h1CommissionTighteningPct", rhythm.commissionTighteningPct());
        cap.put("h1Rhythm", rhythm.summary());
        return cap;
    }

    private List<Map<String, Object>> binarySettlements() {
        String value = configFacade.activeValue(uiConfigKey("F3.binary.rows"))
                .orElseGet(() -> binaryRowsJson(F3_BINARY_SEEDS));
        try {
            List<Map<String, Object>> parsed = JSON.readValue(value, REWARD_LIST_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return F3_BINARY_SEEDS.stream().map(this::binarySettlement).toList();
            }
            return parsed.stream().map(this::normalizeBinarySettlementMap).toList();
        } catch (JsonProcessingException ex) {
            return F3_BINARY_SEEDS.stream().map(this::binarySettlement).toList();
        }
    }

    private Map<String, Object> binarySettlement(F3BinarySettlementSeed seed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user", seed.user());
        row.put("trackA", seed.trackA());
        row.put("trackB", seed.trackB());
        row.put("matchAmount", seed.matchAmount());
        row.put("todayPaid", seed.todayPaid());
        row.put("state", seed.state());
        row.put("tone", seed.tone());
        return row;
    }

    private Map<String, Object> normalizeBinarySettlementMap(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user", String.valueOf(raw.getOrDefault("user", "")));
        row.put("trackA", intValue(raw.get("trackA"), 0));
        row.put("trackB", intValue(raw.get("trackB"), 0));
        row.put("matchAmount", intValue(raw.get("matchAmount"), 0));
        row.put("todayPaid", intValue(raw.get("todayPaid"), 0));
        row.put("state", String.valueOf(raw.getOrDefault("state", "")));
        row.put("tone", String.valueOf(raw.getOrDefault("tone", "")));
        return row;
    }

    private int binaryMaxTrackGmv() {
        return binarySettlements().stream()
                .mapToInt(row -> Math.max(intValue(row.get("trackA"), 0), intValue(row.get("trackB"), 0)))
                .max()
                .orElse(1);
    }

    private Map<String, Object> leadershipPoolReadModel() {
        String ratioLabel = configText("F.pool.ratio", "5%");
        BigDecimal poolRatio = percentRatio(ratioLabel, new BigDecimal("0.05"));
        int weeklyGmvUsd = intConfig(uiConfigKey("F4.pool.weeklyGmvUsd"),
                intConfig(uiConfigKey("F.vrank.leadership.weeklyGmvUsdt"), 9746420));
        int weeklyInjectedUsd = BigDecimal.valueOf(weeklyGmvUsd)
                .multiply(poolRatio)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        int unlockRank = intConfig(uiConfigKey("F.vrank.leadership.unlockRank"), 3);
        int topN = intConfig(uiConfigKey("F.vrank.leadership.topN"), 10);
        List<Map<String, Object>> ranks = leadershipRanks();
        int defaultParticipants = ranks.stream()
                .filter(rank -> ((Number) rank.get("v")).intValue() >= unlockRank)
                .mapToInt(rank -> ((Number) rank.get("pop")).intValue())
                .sum();
        List<Map<String, Object>> quotaRows = quotaRows();
        int quotaCurrent = quotaRows.stream().mapToInt(row -> intValue(row.get("current"), 0)).sum();
        int quotaCap = quotaRows.stream().mapToInt(row -> intValue(row.get("cap"), 0)).sum();
        String leaderboardStatus = configText("F.leaderboard.period.status", "active");
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("weeklyInjectedUsd", weeklyInjectedUsd);
        pool.put("weeklyGmvUsd", weeklyGmvUsd);
        pool.put("poolRatio", ratioLabel);
        pool.put("poolRatioValue", poolRatio);
        pool.put("monthlyCapLabel", configText("F.pool.monthlyCap", "$2,600,000"));
        pool.put("monthlyCapUsd", moneyLabelToInt(configText("F.pool.monthlyCap", "$2,600,000"), 2600000));
        pool.put("participantCount", intConfig(uiConfigKey("F4.pool.participantCount"), defaultParticipants));
        pool.put("topN", topN);
        pool.put("topSharePct", leadershipTopConcentrationPct(ranks, topN));
        pool.put("unlockRank", unlockRank);
        pool.put("settlementWindow", configText("F4.pool.settlementWindow", "周日 23:59 UTC"));
        pool.put("settlementDispatchWindow", configText("F4.pool.settlementDispatchWindow", "周一 00:00 UTC"));
        pool.put("quotaRows", quotaRows);
        pool.put("quotaMonthlyStockLabel", configText("F.quota.monthlyStock", "96 台"));
        pool.put("quotaMonthlyStockTotal", quotaCap);
        pool.put("quotaMonthlyStockUsed", quotaCurrent);
        pool.put("quotaMonthlyStockRemaining", Math.max(0, quotaCap - quotaCurrent));
        pool.put("proUnlock", configText("F.quota.proUnlock", "直推 5 / 月业绩 $50k"));
        pool.put("rackUnlock", configText("F.quota.rackUnlock", "直推 15"));
        pool.put("ambassadorBands", ambassadorBands());
        pool.put("ambassadorStatus", configText("F.ambassador.q3-2025.status", "pending"));
        pool.put("ambassadorPendingCount", intConfig(uiConfigKey("F4.ambassador.pendingCount"), 7));
        pool.put("ambassadorBudgetApprovedLabel", configText("F4.ambassador.budgetApprovedLabel", "$48,200"));
        pool.put("ambassadorBudgetCapLabel", configText("F4.ambassador.budgetCapLabel", "$80,000"));
        pool.put("ambassadorKolBudgetPct", intConfig(uiConfigKey("F4.ambassador.kolBudgetPct"), 50));
        pool.put("ambassadorNextQuotaReviewDate", configText("F4.ambassador.nextQuotaReviewDate", "2026-09-01"));
        pool.put("leaderboardPoolLabel", configText("F.leaderboard.poolUsd", "$48,000"));
        pool.put("leaderboardParticipantCount", intConfig(uiConfigKey("F4.leaderboard.participantCount"), 1284));
        pool.put("leaderboardFraudHitCount", intConfig(uiConfigKey("F4.leaderboard.fraudHitCount"), 3));
        pool.put("leaderboardDisqualified", "disqualified".equalsIgnoreCase(leaderboardStatus));
        pool.put("leaderboardPeriodStatus", leaderboardStatus);
        pool.put("podium", leaderboardPodium());
        pool.put("voteWeights", voteWeights());
        return pool;
    }

    private List<Map<String, Object>> f4Metrics(Map<String, Object> pool) {
        return List.of(
                f4Metric(
                        "weeklyLeadershipPool",
                        "领导奖池",
                        moneyCompact(intValue(pool.get("weeklyInjectedUsd"), 0)),
                        pool.get("poolRatio") + " × 周 GMV " + moneyCompact(intValue(pool.get("weeklyGmvUsd"), 0)),
                        "ok"),
                f4Metric(
                        "hardwareQuotaRemaining",
                        "硬件库存余量",
                        pool.get("quotaMonthlyStockRemaining") + " / " + pool.get("quotaMonthlyStockTotal"),
                        "Pro / Rack 月度库存",
                        "warn"),
                f4Metric(
                        "ambassadorPending",
                        "大使待确认",
                        ambassadorMetricValue(String.valueOf(pool.get("ambassadorStatus")), intValue(pool.get("ambassadorPendingCount"), 0)),
                        "预算 " + pool.get("ambassadorBudgetApprovedLabel") + " / " + pool.get("ambassadorBudgetCapLabel"),
                        "cyan"),
                f4Metric(
                        "leaderboardFraudHits",
                        "榜单风控命中",
                        String.valueOf(pool.get("leaderboardFraudHitCount")),
                        boolValue(pool.get("leaderboardDisqualified"), false) ? "已取消违规资格" : "等待运营复核",
                        boolValue(pool.get("leaderboardDisqualified"), false) ? "ok" : "err"));
    }

    private Map<String, Object> f4Metric(String id, String label, String value, String sub, String tone) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("id", id);
        metric.put("label", label);
        metric.put("value", value);
        metric.put("sub", sub);
        metric.put("tone", tone);
        return metric;
    }

    private String ambassadorMetricValue(String status, int pendingCount) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "approved" -> "已批";
            case "rejected" -> "已驳";
            default -> String.valueOf(pendingCount);
        };
    }

    private Map<String, Object> f4Config(Map<String, Object> pool) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("poolRatio", pool.get("poolRatio"));
        config.put("monthlyCap", pool.get("monthlyCapLabel"));
        config.put("proUnlock", pool.get("proUnlock"));
        config.put("rackUnlock", pool.get("rackUnlock"));
        config.put("monthlyStock", pool.get("quotaMonthlyStockLabel"));
        config.put("ambassadorStatus", pool.get("ambassadorStatus"));
        config.put("leaderboardPoolUsd", pool.get("leaderboardPoolLabel"));
        config.put("leaderboardPeriodStatus", pool.get("leaderboardPeriodStatus"));
        return config;
    }

    private List<Map<String, Object>> quotaRows() {
        String value = configFacade.activeValue(uiConfigKey("F4.quota.rows"))
                .orElseGet(() -> f4QuotaRowsJson(F4_QUOTA_SEEDS));
        try {
            List<Map<String, Object>> parsed = JSON.readValue(value, REWARD_LIST_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return F4_QUOTA_SEEDS.stream().map(this::quotaRow).toList();
            }
            return parsed.stream().map(this::normalizeQuotaRow).toList();
        } catch (JsonProcessingException ex) {
            return F4_QUOTA_SEEDS.stream().map(this::quotaRow).toList();
        }
    }

    private Map<String, Object> quotaRow(F4QuotaSeed seed) {
        return quotaRow(seed.name(), seed.current(), seed.cap(), seed.tight());
    }

    private Map<String, Object> quotaRow(String name, int current, int cap, boolean tight) {
        return Map.of("name", name, "current", current, "cap", cap, "tight", tight);
    }

    private Map<String, Object> normalizeQuotaRow(Map<String, Object> raw) {
        int current = intValue(raw.get("current"), 0);
        int cap = Math.max(current, intValue(raw.get("cap"), current));
        return quotaRow(
                String.valueOf(raw.getOrDefault("name", "Quota")),
                current,
                cap,
                boolValue(raw.get("tight"), cap > 0 && current * 100 / cap >= 85));
    }

    private List<Map<String, Object>> ambassadorBands() {
        String value = configFacade.activeValue(uiConfigKey("F4.ambassador.bands"))
                .orElseGet(() -> f4AmbassadorBandsJson(F4_AMBASSADOR_BAND_SEEDS));
        try {
            List<Map<String, Object>> parsed = JSON.readValue(value, REWARD_LIST_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return F4_AMBASSADOR_BAND_SEEDS.stream().map(this::ambassadorBand).toList();
            }
            return parsed.stream().map(this::normalizeAmbassadorBand).toList();
        } catch (JsonProcessingException ex) {
            return F4_AMBASSADOR_BAND_SEEDS.stream().map(this::ambassadorBand).toList();
        }
    }

    private Map<String, Object> ambassadorBand(F4AmbassadorBandSeed seed) {
        return Map.of("name", seed.name(), "count", seed.count());
    }

    private Map<String, Object> normalizeAmbassadorBand(Map<String, Object> raw) {
        return Map.of(
                "name", String.valueOf(raw.getOrDefault("name", "BAND")),
                "count", intValue(raw.get("count"), 0));
    }

    private List<Map<String, Object>> leaderboardPodium() {
        String value = configFacade.activeValue(uiConfigKey("F4.leaderboard.podium"))
                .orElseGet(() -> f4PodiumJson(F4_PODIUM_SEEDS));
        try {
            List<Map<String, Object>> parsed = JSON.readValue(value, REWARD_LIST_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return F4_PODIUM_SEEDS.stream().map(this::podium).toList();
            }
            return parsed.stream().map(this::normalizePodium).toList();
        } catch (JsonProcessingException ex) {
            return F4_PODIUM_SEEDS.stream().map(this::podium).toList();
        }
    }

    private Map<String, Object> podium(F4PodiumSeed seed) {
        return podium(seed.rank(), seed.userId(), seed.gmvLabel(), seed.tip(), seed.className());
    }

    private Map<String, Object> podium(int rank, String userId, String gmvLabel, String tip, String className) {
        return Map.of(
                "rank", rank,
                "userId", userId,
                "gmvLabel", gmvLabel,
                "tip", tip,
                "className", className);
    }

    private Map<String, Object> normalizePodium(Map<String, Object> raw) {
        return podium(
                intValue(raw.get("rank"), 0),
                String.valueOf(raw.getOrDefault("userId", "")),
                String.valueOf(raw.getOrDefault("gmvLabel", "-")),
                String.valueOf(raw.getOrDefault("tip", "")),
                String.valueOf(raw.getOrDefault("className", "")));
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
                "monthlyCommissionSpendLabel", configText("F5.commission.monthlySpendLabel", "$8.42M"),
                "coolingBalanceLabel", configText("F5.commission.coolingBalanceLabel", "$2.46M"),
                "withdrawableThisMonthLabel", configText("F5.commission.withdrawableThisMonthLabel", "$5.96M"),
                "abnormalOrFrozenCount", abnormalOrFrozen);
    }

    private List<Map<String, Object>> commissionKinds() {
        return configRows("F5.commission.kinds", this::normalizeCommissionKind, "F5 commission kinds");
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
        return configRows("F5.commission.filters", this::normalizeCommissionFilter, "F5 commission filters");
    }

    private List<Map<String, Object>> commissionEvents() {
        return configRows("F5.commission.events", this::normalizeCommissionEvent, "F5 commission events");
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
        return configRows("F5.commission.auditFeed", this::normalizeCommissionAuditFeedItem, "F5 commission audit feed");
    }

    private List<Map<String, Object>> configRows(
            String key,
            Function<Map<String, Object>, Map<String, Object>> normalizer,
            String label) {
        String configKey = uiConfigKey(key);
        String value = configFacade.activeValue(configKey)
                .orElseThrow(() -> new IllegalStateException(label + " config is missing"));
        try {
            List<Map<String, Object>> parsed = JSON.readValue(value, REWARD_LIST_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                throw new IllegalStateException(label + " config is empty");
            }
            return parsed.stream().map(normalizer).toList();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid " + label + " config", ex);
        }
    }

    private Map<String, Object> normalizeCommissionKind(Map<String, Object> raw) {
        return commissionKind(
                textValue(raw, "key", "all"),
                textValue(raw, "code", "ALL"),
                textValue(raw, "label", "全部佣金类型"),
                textValue(raw, "amountLabel", "$0"),
                textValue(raw, "countLabel", "0 笔"),
                textValue(raw, "className", ""),
                textValue(raw, "amountColor", ""));
    }

    private Map<String, Object> normalizeCommissionFilter(Map<String, Object> raw) {
        return Map.of(
                "key", textValue(raw, "key", "all"),
                "label", textValue(raw, "label", "全部状态"));
    }

    private Map<String, Object> normalizeCommissionEvent(Map<String, Object> raw) {
        return commissionEvent(
                textValue(raw, "id", ""),
                textValue(raw, "kind", "network"),
                textValue(raw, "user", "usr_unknown"),
                decimalValue(raw.get("amount"), BigDecimal.ZERO),
                textValue(raw, "currency", "USDT"),
                intValue(raw.get("cooldownPercent"), 0),
                textValue(raw, "cooldownLabel", "冷却中"),
                textValue(raw, "state", "计提"));
    }

    private Map<String, Object> normalizeCommissionAuditFeedItem(Map<String, Object> raw) {
        return Map.of(
                "when", textValue(raw, "when", "-"),
                "text", textValue(raw, "text", ""),
                "level", textValue(raw, "level", "LOW"));
    }

    private String textValue(Map<String, Object> raw, String key, String fallback) {
        Object value = raw.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return fallback;
        }
        return String.valueOf(value).trim();
    }

    private BigDecimal decimalValue(Object raw, BigDecimal fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number) {
            return parseDecimal(String.valueOf(raw), fallback);
        }
        return parseDecimal(String.valueOf(raw), fallback);
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

    private void seedVRankIfMissing() {
        for (VRankSeed seed : VRANK_SEEDS) {
            seedText("F.vrank." + seed.v() + ".pop", String.valueOf(seed.pop()), "F1 V-Rank member population");
            seedText("F.vrank." + seed.v() + ".selfBuy", seed.selfBuy(), "F1 V-Rank self purchase threshold");
            seedText("F.vrank." + seed.v() + ".directRefs", seed.directRefs(), "F1 V-Rank direct referral threshold");
            seedText("F.vrank." + seed.v() + ".teamGv", seed.teamGv(), "F1 V-Rank team GV threshold");
            seedText("F.vrank." + seed.v() + ".legCount", seed.legCount(), "F1 V-Rank qualified leg threshold");
            seedText("F.vrank." + seed.v() + ".legRank", seed.legRank(), "F1 V-Rank qualified leg rank threshold");
            seedJson("F.vrank.rewards." + seed.v(), rewardsJson(VRANK_REWARD_SEEDS.getOrDefault(seed.v(), List.of())),
                    "F1 V-Rank reward list");
        }
        seedText("F.vrank.leadership.weeklyGmvUsdt", "9746420", "F1 leadership pool weekly GMV");
        seedText("F.vrank.leadership.poolRatio", "0.05", "F1 leadership pool ratio");
        seedText("F.vrank.leadership.monthlyCapUsdt", "2600000", "F1 leadership monthly cap");
        seedText("F.vrank.leadership.unlockRank", "3", "F1 leadership unlock rank");
        seedText("F.vrank.leadership.topN", "10", "F1 leadership top-N concentration");
        int votes = 1;
        for (int i = 3; i <= 12; i++) {
            seedText("F.pool.votes.V" + i, String.valueOf(votes), "F1 leadership vote weight");
            votes *= 2;
        }
    }

    private void seedF2RatesIfMissing() {
        for (F2MetricSeed seed : F2_METRIC_SEEDS) {
            String prefix = "F2.metric." + seed.id();
            seedText(prefix + ".label", seed.label(), "F2 metric label");
            seedText(prefix + ".value", seed.value(), "F2 metric value");
            seedText(prefix + ".sub", seed.sub(), "F2 metric subtitle");
            seedText(prefix + ".tone", seed.tone(), "F2 metric tone");
        }
        for (F2UnilevelSeed seed : F2_UNILEVEL_SEEDS) {
            String prefix = "F.unilevel." + seed.level();
            seedText(prefix, seed.usdtPct().stripTrailingZeros().toPlainString(), "F2 unilevel USDT royalty rate");
            seedText("F.unilevel.nex." + seed.level(), seed.nexReward().stripTrailingZeros().toPlainString(), "F2 unilevel NEX reward per USDT");
            seedText(prefix + ".label", seed.label(), "F2 unilevel label");
            seedText(prefix + ".direct", seed.direct() ? "true" : "false", "F2 unilevel direct flag");
        }
        for (F2RateTierSeed seed : F2_RATE_TIER_SEEDS) {
            String prefix = "F.rateTier." + seed.name();
            seedText(prefix + ".name", seed.name(), "F2 rate tier name");
            seedText(prefix + ".requirement", seed.requirement(), "F2 rate tier requirement");
            seedText(prefix + ".rate", seed.rate(), "F2 rate tier rate");
            seedText(prefix + ".distribution", seed.distribution(), "F2 rate tier distribution");
            seedText(prefix + ".className", seed.className(), "F2 rate tier UI class");
        }
        for (F2PolicyParamSeed seed : F2_POLICY_PARAM_SEEDS) {
            seedText(seed.key(), seed.defaultValue(), "F2 policy parameter");
        }
    }

    private void seedF3BinaryIfMissing() {
        for (F3MetricSeed seed : F3_METRIC_SEEDS) {
            String prefix = "F3.metric." + seed.id();
            seedText(prefix + ".label", seed.label(), "F3 binary metric label");
            seedText(prefix + ".value", seed.value(), "F3 binary metric value");
            seedText(prefix + ".sub", seed.sub(), "F3 binary metric subtitle");
            seedText(prefix + ".tone", seed.tone(), "F3 binary metric tone");
        }
        seedJson("F3.binary.rows", binaryRowsJson(F3_BINARY_SEEDS), "F3 binary settlement rows");
        seedText("F3.formula.user", "usr_31E8", "F3 binary formula sample user");
        seedText("F3.formula.trackA", "84000", "F3 binary formula Track A");
        seedText("F3.formula.trackB", "62000", "F3 binary formula Track B");
        seedText("F3.formula.matchAmount", "6200", "F3 binary formula match amount");
        seedText("F3.dailyCap.currentLabel", "$5,000", "F3 binary daily cap current label");
        seedText("F3.dailyCap.windowLabel", "月 1-6 现值 · 全局统一", "F3 binary daily cap window");
        seedText("F3.dailyCap.nextTrigger", "月 7", "F3 binary daily cap next trigger");
        seedText("F3.dailyCap.nextLabel", "$2,000", "F3 binary daily cap next value");
        seedText("F3.dailyCap.currentMonth", "6", "F3 binary daily cap current month");
        seedText("F3.dailyCap.currentPhase", "收缩期", "F3 binary daily cap current phase");
        seedText("F.binary.threshold", "$1,000 / 轨", "F3 binary threshold");
        seedText("F.binary.matchRate", "10%", "F3 binary balance match rate");
        seedText("F.binary.spillover", "已启用", "F3 binary spillover placement");
        seedText("F.binary.gvResetCron", "每月 1 日 00:00 UTC", "F3 binary GV reset schedule");
        seedText("F.binary.settlePeriod", "每月", "F3 binary settlement period");
        seedText("F.binary.residualPolicy", "每月清零", "F3 binary residual policy");
        seedText("F.binary.residualPool", "$1.2M", "F3 binary residual pool");
        seedText("F.binary.residualSub", "月底归零 · 不结转", "F3 binary residual subtitle");
        seedText("F.binary.participantCount", "1842", "F3 binary settlement participant count");
        seedText("F.binary.blockedCount", "468", "F3 binary blocked user count");
        seedText("F.binary.monthlyMatchedUsd", "214800", "F3 binary monthly matched amount");
        seedText("F.binary.autoPlacement7dCount", "1284", "F3 binary 7-day auto placement count");
        seedText("F.binary.dailyMatchUsd", "10490", "F3 binary daily matched amount");
    }

    private void seedF4LeadershipPoolIfMissing() {
        seedText("F.pool.ratio", "5%", "F4 leadership pool inject ratio");
        seedText("F.pool.monthlyCap", "$2,600,000", "F4 leadership pool monthly cap");
        seedText("F.quota.proUnlock", "直推 5 / 月业绩 $50k", "F4 Pro quota unlock rule");
        seedText("F.quota.rackUnlock", "直推 15", "F4 Rack quota unlock rule");
        seedText("F.quota.monthlyStock", "96 台", "F4 quota monthly stock");
        seedText("F.ambassador.q3-2025.status", "pending", "F4 ambassador quarter decision status");
        seedText("F.leaderboard.poolUsd", "$48,000", "F4 leaderboard reward pool");
        seedText("F.leaderboard.period.status", "active", "F4 leaderboard period status");
        seedText("F4.pool.weeklyGmvUsd", "9746420", "F4 leadership pool weekly GMV");
        seedText("F4.pool.participantCount", "496", "F4 leadership pool qualified participants");
        seedText("F4.pool.settlementWindow", "周日 23:59 UTC", "F4 leadership pool snapshot window");
        seedText("F4.pool.settlementDispatchWindow", "周一 00:00 UTC", "F4 leadership pool dispatch window");
        seedJson("F4.quota.rows", f4QuotaRowsJson(F4_QUOTA_SEEDS), "F4 quota rows");
        seedJson("F4.ambassador.bands", f4AmbassadorBandsJson(F4_AMBASSADOR_BAND_SEEDS), "F4 ambassador request bands");
        seedText("F4.ambassador.pendingCount", "7", "F4 ambassador pending request count");
        seedText("F4.ambassador.budgetApprovedLabel", "$48,200", "F4 ambassador approved budget label");
        seedText("F4.ambassador.budgetCapLabel", "$80,000", "F4 ambassador budget cap label");
        seedText("F4.ambassador.kolBudgetPct", "50", "F4 ambassador KOL budget percentage");
        seedText("F4.ambassador.nextQuotaReviewDate", "2026-09-01", "F4 ambassador next quota review date");
        seedText("F4.leaderboard.participantCount", "1284", "F4 leaderboard participant count");
        seedText("F4.leaderboard.fraudHitCount", "3", "F4 leaderboard fraud hit count");
        seedJson("F4.leaderboard.podium", f4PodiumJson(F4_PODIUM_SEEDS), "F4 leaderboard podium rows");
    }

    private void seedF5CommissionAuditIfMissing() {
        seedText("F5.commission.monthlySpendLabel", "$8.42M", "F5 monthly commission spend label");
        seedText("F5.commission.coolingBalanceLabel", "$2.46M", "F5 cooling commission balance label");
        seedText("F5.commission.withdrawableThisMonthLabel", "$5.96M", "F5 withdrawable commission label");
        seedJson("F5.commission.kinds", mapRowsJson(F5_COMMISSION_KIND_SEEDS, "F5 commission kinds"),
                "F5 commission kind cards");
        seedJson("F5.commission.filters", mapRowsJson(F5_COMMISSION_FILTER_SEEDS, "F5 commission filters"),
                "F5 commission status filters");
        seedJson("F5.commission.events", mapRowsJson(F5_COMMISSION_EVENT_SEEDS, "F5 commission events"),
                "F5 commission audit rows");
        seedJson("F5.commission.auditFeed", mapRowsJson(F5_COMMISSION_AUDIT_FEED_SEEDS, "F5 commission audit feed"),
                "F5 commission audit feed");
        for (Map<String, Object> event : F5_COMMISSION_EVENT_SEEDS) {
            seedText(
                    "F.commission." + event.get("id") + ".status",
                    String.valueOf(event.get("state")),
                    "F5 commission event status");
        }
    }

    private void seedText(String key, String value, String remark) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String configKey = uiConfigKey(key);
        if (configFacade.activeValue(configKey).isEmpty()) {
            try {
                configFacade.upsertAdminValue(configKey, value, "TEXT", "team", remark);
            } catch (RuntimeException ex) {
                if (!isDuplicateConfigKey(ex)) {
                    throw ex;
                }
            }
        }
    }

    private void seedJson(String key, String value, String remark) {
        String configKey = uiConfigKey(key);
        if (configFacade.activeValue(configKey).isEmpty()) {
            try {
                configFacade.upsertAdminValue(configKey, value, "JSON", "team", remark);
            } catch (RuntimeException ex) {
                if (!isDuplicateConfigKey(ex)) {
                    throw ex;
                }
            }
        }
    }

    private boolean isDuplicateConfigKey(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Duplicate entry") && message.contains("uk_config_key")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, List<Map<String, Object>>> vRankRewards() {
        Map<String, List<Map<String, Object>>> rewards = new LinkedHashMap<>();
        for (String level : VRANK_LEVELS) {
            rewards.put(level, activeRewards(level));
        }
        return rewards;
    }

    private List<Map<String, Object>> activeRewards(String level) {
        String value = configFacade.activeValue(rewardConfigKey(level))
                .orElseGet(() -> rewardsJson(VRANK_REWARD_SEEDS.getOrDefault(level, List.of())));
        try {
            List<Map<String, Object>> parsed = JSON.readValue(value, REWARD_LIST_TYPE);
            return parsed == null ? List.of() : parsed.stream().map(this::normalizeRewardMap).toList();
        } catch (JsonProcessingException ex) {
            return VRANK_REWARD_SEEDS.getOrDefault(level, List.of());
        }
    }

    private Map<String, Object> normalizeRewardMap(Map<String, Object> raw) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(raw.getOrDefault("id", "")));
        item.put("type", String.valueOf(raw.getOrDefault("type", "custom")));
        if (raw.containsKey("amount")) {
            item.put("amount", parseDecimal(String.valueOf(raw.get("amount")), BigDecimal.ZERO).stripTrailingZeros());
        }
        copyText(raw, item, "voucherId");
        copyText(raw, item, "skuId");
        copyText(raw, item, "custom");
        return item;
    }

    private void copyText(Map<String, Object> raw, Map<String, Object> target, String key) {
        Object value = raw.get(key);
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            target.put(key, String.valueOf(value));
        }
    }

    private void persistRewards(String level, List<Map<String, Object>> rewards) {
        configFacade.upsertAdminValue(rewardConfigKey(level), rewardsJson(rewards), "JSON", "team", "F1 V-Rank reward list");
    }

    private String rewardConfigKey(String level) {
        return uiConfigKey("F.vrank.rewards." + level);
    }

    private Map<String, Object> normalizeReward(String id, VRankRewardRequest request) {
        String type = requireText(request.type(), "TEAM_VRANK_REWARD_TYPE_REQUIRED").toLowerCase(Locale.ROOT);
        if (!VRANK_REWARD_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported F1 V-Rank reward type");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", requireText(id, "TEAM_VRANK_REWARD_ID_REQUIRED"));
        item.put("type", type);
        if ("usdt".equals(type) || "nex".equals(type)) {
            if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("F1 V-Rank fund reward amount must be positive");
            }
            item.put("amount", request.amount().stripTrailingZeros());
            return item;
        }
        if ("voucher".equals(type)) {
            item.put("voucherId", requireText(request.voucherId(), "TEAM_VRANK_REWARD_VOUCHER_REQUIRED"));
            return item;
        }
        if ("sku".equals(type)) {
            item.put("skuId", requireText(request.skuId(), "TEAM_VRANK_REWARD_SKU_REQUIRED"));
            return item;
        }
        item.put("custom", requireText(request.custom(), "TEAM_VRANK_REWARD_CUSTOM_REQUIRED"));
        return item;
    }

    private String rewardsJson(List<Map<String, Object>> rewards) {
        try {
            return JSON.writeValueAsString(rewards);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize F1 V-Rank rewards", ex);
        }
    }

    private String binaryRowsJson(List<F3BinarySettlementSeed> rows) {
        try {
            return JSON.writeValueAsString(rows.stream().map(this::binarySettlement).toList());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize F3 binary settlement rows", ex);
        }
    }

    private String f4QuotaRowsJson(List<F4QuotaSeed> rows) {
        return mapRowsJson(rows.stream().map(this::quotaRow).toList(), "F4 quota rows");
    }

    private String f4AmbassadorBandsJson(List<F4AmbassadorBandSeed> rows) {
        return mapRowsJson(rows.stream().map(this::ambassadorBand).toList(), "F4 ambassador bands");
    }

    private String f4PodiumJson(List<F4PodiumSeed> rows) {
        return mapRowsJson(rows.stream().map(this::podium).toList(), "F4 leaderboard podium");
    }

    private String mapRowsJson(List<Map<String, Object>> rows, String label) {
        try {
            return JSON.writeValueAsString(rows);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize " + label, ex);
        }
    }

    private String nextRewardId(String level) {
        return "vr-" + level + "-" + System.currentTimeMillis() + "-" + REWARD_SEQUENCE.incrementAndGet();
    }

    private List<String> voucherOptions() {
        return vRankRewards().values().stream()
                .flatMap(List::stream)
                .filter(item -> "voucher".equals(item.get("type")))
                .map(item -> String.valueOf(item.get("voucherId")))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> skuOptions() {
        return vRankRewards().values().stream()
                .flatMap(List::stream)
                .filter(item -> "sku".equals(item.get("type")))
                .map(item -> String.valueOf(item.get("skuId")))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private Map<String, String> labelMap(List<String> ids) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String id : ids) {
            labels.put(id, id);
        }
        return labels;
    }

    private Map<String, Object> leadershipSnapshot() {
        int unlockRank = intConfig(uiConfigKey("F.vrank.leadership.unlockRank"), 3);
        int topN = intConfig(uiConfigKey("F.vrank.leadership.topN"), 10);
        List<Map<String, Object>> ranks = leadershipRanks();
        int totalMembers = VRANK_SEEDS.stream().mapToInt(seed -> intConfig(uiConfigKey("F.vrank." + seed.v() + ".pop"), seed.pop())).sum();
        int qualifiers = ranks.stream()
                .filter(rank -> ((Number) rank.get("v")).intValue() >= unlockRank)
                .mapToInt(rank -> ((Number) rank.get("pop")).intValue())
                .sum();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("weeklyGmvUsdt", configDecimal(uiConfigKey("F.vrank.leadership.weeklyGmvUsdt"), new BigDecimal("9746420")));
        response.put("poolRatio", configDecimal(uiConfigKey("F.vrank.leadership.poolRatio"), new BigDecimal("0.05")));
        response.put("monthlyCapUsdt", configDecimal(uiConfigKey("F.vrank.leadership.monthlyCapUsdt"), new BigDecimal("2600000")));
        response.put("unlockRank", unlockRank);
        response.put("topN", topN);
        response.put("ranks", ranks);
        response.put("topConcentrationPct", leadershipTopConcentrationPct(ranks, topN));
        response.put("qualifiers", qualifiers);
        response.put("totalMembers", totalMembers);
        return response;
    }

    private List<Map<String, Object>> leadershipRanks() {
        List<Map<String, Object>> ranks = new ArrayList<>();
        for (int i = 3; i <= 12; i++) {
            VRankSeed seed = requireVRank("V" + i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("v", i);
            row.put("votes", intConfig(uiConfigKey("F.pool.votes.V" + i), (int) Math.pow(2, i - 3)));
            row.put("pop", intConfig(uiConfigKey("F.vrank.V" + i + ".pop"), seed.pop()));
            ranks.add(row);
        }
        return ranks;
    }

    private int leadershipTopConcentrationPct(List<Map<String, Object>> ranks, int topN) {
        int totalVotes = ranks.stream()
                .mapToInt(rank -> ((Number) rank.get("votes")).intValue() * ((Number) rank.get("pop")).intValue())
                .sum();
        if (totalVotes <= 0) {
            return 0;
        }
        int remaining = topN;
        int topVotes = 0;
        List<Map<String, Object>> sorted = ranks.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> rank) -> ((Number) rank.get("votes")).intValue()).reversed())
                .toList();
        for (Map<String, Object> rank : sorted) {
            int take = Math.min(remaining, ((Number) rank.get("pop")).intValue());
            topVotes += take * ((Number) rank.get("votes")).intValue();
            remaining -= take;
            if (remaining <= 0) {
                break;
            }
        }
        return BigDecimal.valueOf(topVotes)
                .multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(totalVotes), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private VRankSeed requireVRank(String rank) {
        String normalized = requireText(rank, "TEAM_VRANK_REQUIRED").toUpperCase(Locale.ROOT);
        return VRANK_SEEDS.stream()
                .filter(seed -> seed.v().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported F1 V-Rank level"));
    }

    private Set<String> thresholdFields(VRankSeed seed) {
        Set<String> fields = new LinkedHashSet<>();
        if (seed.selfBuy() != null) {
            fields.add("selfBuy");
        }
        if (seed.directRefs() != null) {
            fields.add("directRefs");
        }
        if (seed.teamGv() != null) {
            fields.add("teamGv");
        }
        if (seed.legCount() != null) {
            fields.add("legCount");
        }
        if (seed.legRank() != null) {
            fields.add("legRank");
        }
        return fields;
    }

    private String defaultField(VRankSeed seed, String field) {
        return switch (field) {
            case "selfBuy" -> seed.selfBuy();
            case "directRefs" -> seed.directRefs();
            case "teamGv" -> seed.teamGv();
            case "legCount" -> seed.legCount();
            case "legRank" -> seed.legRank();
            default -> "";
        };
    }

    private String normalizeVRankThreshold(String field, String raw) {
        String value = normalizeUiValue(raw);
        if ("legRank".equals(field) && !VRANK_LEVELS.contains(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("F1 V-Rank leg rank must be one of V0-V12");
        }
        if (("directRefs".equals(field) || "legCount".equals(field)) && parseDecimal(value).compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("F1 V-Rank count threshold must be non-negative");
        }
        return "legRank".equals(field) ? value.toUpperCase(Locale.ROOT) : value;
    }

    private int intConfig(String key, int fallback) {
        return configDecimal(key, BigDecimal.valueOf(fallback)).setScale(0, RoundingMode.DOWN).intValue();
    }

    private String uiConfigKey(String key) {
        return "team.ui." + key;
    }

    private static Set<String> uiConfigKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (VRankSeed seed : VRANK_SEEDS) {
            keys.add("F.vrank." + seed.v());
            keys.add("F.vrank." + seed.v() + ".pop");
            keys.add("F.vrank.rewards." + seed.v());
            for (String field : staticThresholdFields(seed)) {
                keys.add("F.vrank." + seed.v() + "." + field);
            }
        }
        keys.addAll(List.of(
                "F.vrank.leadership.weeklyGmvUsdt",
                "F.vrank.leadership.poolRatio",
                "F.vrank.leadership.monthlyCapUsdt",
                "F.vrank.leadership.unlockRank",
                "F.vrank.leadership.topN"));
        for (int i = 1; i <= 12; i++) {
            keys.add("F.fulfillment.V" + i + ".queue.status");
        }
        for (int i = 1; i <= 7; i++) {
            keys.add("F.unilevel.L" + i);
            keys.add("F.unilevel.nex.L" + i);
            keys.add("F.unilevel.L" + i + ".label");
            keys.add("F.unilevel.L" + i + ".direct");
        }
        for (F2MetricSeed seed : F2_METRIC_SEEDS) {
            keys.add("F2.metric." + seed.id() + ".label");
            keys.add("F2.metric." + seed.id() + ".value");
            keys.add("F2.metric." + seed.id() + ".sub");
            keys.add("F2.metric." + seed.id() + ".tone");
        }
        for (F3MetricSeed seed : F3_METRIC_SEEDS) {
            keys.add("F3.metric." + seed.id() + ".label");
            keys.add("F3.metric." + seed.id() + ".value");
            keys.add("F3.metric." + seed.id() + ".sub");
            keys.add("F3.metric." + seed.id() + ".tone");
        }
        for (F2RateTierSeed seed : F2_RATE_TIER_SEEDS) {
            String prefix = "F.rateTier." + seed.name();
            keys.add(prefix + ".name");
            keys.add(prefix + ".requirement");
            keys.add(prefix + ".rate");
            keys.add(prefix + ".distribution");
            keys.add(prefix + ".className");
        }
        for (F2PolicyParamSeed seed : F2_POLICY_PARAM_SEEDS) {
            keys.add(seed.key());
        }
        keys.addAll(List.of(
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
                "F.binary.settlePeriod",
                "F.binary.residualPolicy",
                "F.binary.residualPool",
                "F.binary.residualSub",
                "F.binary.participantCount",
                "F.binary.blockedCount",
                "F.binary.monthlyMatchedUsd",
                "F.binary.autoPlacement7dCount",
                "F.binary.dailyMatchUsd",
                "F3.binary.rows",
                "F3.formula.user",
                "F3.formula.trackA",
                "F3.formula.trackB",
                "F3.formula.matchAmount",
                "F3.dailyCap.currentLabel",
                "F3.dailyCap.windowLabel",
                "F3.dailyCap.nextTrigger",
                "F3.dailyCap.nextLabel",
                "F3.dailyCap.currentMonth",
                "F3.dailyCap.currentPhase",
                "F.pool.ratio",
                "F.pool.monthlyCap",
                "F.quota.proUnlock",
                "F.quota.rackUnlock",
                "F.quota.monthlyStock",
                "F.ambassador.q3-2025.status",
                "F.leaderboard.poolUsd",
                "F.leaderboard.period.status",
                "F4.pool.weeklyGmvUsd",
                "F4.pool.participantCount",
                "F4.pool.settlementWindow",
                "F4.pool.settlementDispatchWindow",
                "F4.quota.rows",
                "F4.ambassador.bands",
                "F4.ambassador.pendingCount",
                "F4.ambassador.budgetApprovedLabel",
                "F4.ambassador.budgetCapLabel",
                "F4.ambassador.kolBudgetPct",
                "F4.ambassador.nextQuotaReviewDate",
                "F4.leaderboard.participantCount",
                "F4.leaderboard.fraudHitCount",
                "F4.leaderboard.podium",
                "F5.commission.monthlySpendLabel",
                "F5.commission.coolingBalanceLabel",
                "F5.commission.withdrawableThisMonthLabel",
                "F5.commission.kinds",
                "F5.commission.filters",
                "F5.commission.events",
                "F5.commission.auditFeed"));
        for (int i = 3; i <= 12; i++) {
            keys.add("F.pool.votes.V" + i);
        }
        for (Map<String, Object> event : F5_COMMISSION_EVENT_SEEDS) {
            keys.add("F.commission." + event.get("id") + ".status");
        }
        return Collections.unmodifiableSet(keys);
    }

    private static Set<String> staticThresholdFields(VRankSeed seed) {
        Set<String> fields = new LinkedHashSet<>();
        if (seed.selfBuy() != null) {
            fields.add("selfBuy");
        }
        if (seed.directRefs() != null) {
            fields.add("directRefs");
        }
        if (seed.teamGv() != null) {
            fields.add("teamGv");
        }
        if (seed.legCount() != null) {
            fields.add("legCount");
        }
        if (seed.legRank() != null) {
            fields.add("legRank");
        }
        return fields;
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

    private String configText(String key, String fallback) {
        return configFacade.activeValue(uiConfigKey(key))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .orElse(fallback);
    }

    private boolean boolConfig(String key, boolean fallback) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .map(value -> {
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    return "true".equals(normalized) || "1".equals(normalized) || "on".equals(normalized);
                })
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

    private BigDecimal percentRatio(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String value = raw.trim();
        if (value.endsWith("%")) {
            return parseDecimal(value, fallback.multiply(new BigDecimal("100")))
                    .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        }
        BigDecimal parsed = parseDecimal(value, fallback);
        return parsed.compareTo(BigDecimal.ONE) > 0
                ? parsed.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                : parsed;
    }

    private int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(0, RoundingMode.DOWN).intValue();
        }
        if (raw == null) {
            return fallback;
        }
        return parseDecimal(String.valueOf(raw), BigDecimal.valueOf(fallback)).setScale(0, RoundingMode.DOWN).intValue();
    }

    private boolean boolValue(Object raw, boolean fallback) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
            return fallback;
        }
        String normalized = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "on".equals(normalized);
    }

    private int moneyLabelToInt(String raw, int fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String normalized = raw.trim()
                .replace("$", "")
                .replace(",", "")
                .replace("USDT", "")
                .replace("usdt", "")
                .trim();
        BigDecimal multiplier = BigDecimal.ONE;
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.endsWith("K")) {
            multiplier = new BigDecimal("1000");
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (upper.endsWith("M")) {
            multiplier = new BigDecimal("1000000");
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return parseDecimal(normalized, BigDecimal.valueOf(fallback))
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private String moneyCompact(int value) {
        if (Math.abs(value) >= 1_000_000) {
            return "$" + BigDecimal.valueOf(value)
                    .divide(new BigDecimal("1000000"), 2, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString() + "M";
        }
        if (Math.abs(value) >= 1_000) {
            return "$" + BigDecimal.valueOf(value)
                    .divide(new BigDecimal("1000"), 1, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString() + "k";
        }
        return "$" + value;
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

    private static List<F3BinarySettlementSeed> binarySettlementSeeds() {
        List<F3BinarySettlementSeed> seeds = new ArrayList<>();
        seeds.add(new F3BinarySettlementSeed("usr_31E8", 84000, 62000, 6200, 1500, "结算中", "ok"));
        seeds.add(new F3BinarySettlementSeed("usr_19C7", 38000, 41000, 3800, 1500, "达封顶", "warn"));
        seeds.add(new F3BinarySettlementSeed("usr_02A9", 12000, 800, 0, 0, "阻塞 · 弱轨 < $1k", "err"));
        seeds.add(new F3BinarySettlementSeed("usr_84F2", 5400, 4900, 490, 490, "结算中", "ok"));
        for (int i = 0; i < 26; i++) {
            String user = "usr_" + String.format("%04X", (0xA100 + i * 0x1D7) & 0xFFFF);
            int trackA = 2200 + ((i * 7919) % 79000);
            int trackB = 600 + ((i * 6131) % 77000);
            boolean blocked = Math.min(trackA, trackB) < 1000;
            int matchAmount = blocked ? 0 : (int) (Math.round((Math.min(trackA, trackB) * 0.1) / 10.0) * 10);
            boolean capped = !blocked && matchAmount >= 1500;
            int todayPaid = blocked ? 0 : Math.min(matchAmount, 1500);
            seeds.add(new F3BinarySettlementSeed(
                    user,
                    trackA,
                    trackB,
                    matchAmount,
                    todayPaid,
                    blocked ? "阻塞 · 弱轨 < $1k" : capped ? "达封顶" : "结算中",
                    blocked ? "err" : capped ? "warn" : "ok"));
        }
        return Collections.unmodifiableList(seeds);
    }

    private static Map<String, List<Map<String, Object>>> vrankRewardSeeds() {
        Map<String, List<Map<String, Object>>> seeds = new LinkedHashMap<>();
        for (String level : VRANK_LEVELS) {
            seeds.put(level, List.of());
        }
        seeds.put("V1", List.of(rewardSeed("vr-V1-nex", "nex", new BigDecimal("500"), null, null, null)));
        seeds.put("V2", List.of(rewardSeed("vr-V2-nex", "nex", new BigDecimal("2000"), null, null, null)));
        seeds.put("V3", List.of(rewardSeed("vr-V3-nex", "nex", new BigDecimal("10000"), null, null, null)));
        seeds.put("V4", List.of(rewardSeed("vr-V4-nex", "nex", new BigDecimal("50000"), null, null, null)));
        seeds.put("V5", List.of(rewardSeed("vr-V5-nex", "nex", new BigDecimal("200000"), null, null, null)));
        seeds.put("V6", List.of(rewardSeed("vr-V6-nex", "nex", new BigDecimal("800000"), null, null, null)));
        seeds.put("V7", List.of(rewardSeed("vr-V7-nex", "nex", new BigDecimal("3200000"), null, null, null)));
        seeds.put("V8", List.of(rewardSeed("vr-V8-nex", "nex", new BigDecimal("10000000"), null, null, null)));
        return Collections.unmodifiableMap(seeds);
    }

    private static Map<String, Object> rewardSeed(
            String id, String type, BigDecimal amount, String voucherId, String skuId, String custom) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        if (amount != null) {
            item.put("amount", amount);
        }
        if (StringUtils.hasText(voucherId)) {
            item.put("voucherId", voucherId);
        }
        if (StringUtils.hasText(skuId)) {
            item.put("skuId", skuId);
        }
        if (StringUtils.hasText(custom)) {
            item.put("custom", custom);
        }
        return Collections.unmodifiableMap(item);
    }

    private static List<Map<String, Object>> f5CommissionKinds() {
        return List.of(
                f5Kind("all", "ALL", "全部佣金类型", "$8.42M", "12,847 笔", "k-network", ""),
                f5Kind("network", "NETWORK", "L1-L7 网络版税", "$3.21M", "8,124 笔", "k-network", "var(--brand)"),
                f5Kind("binary", "BINARY", "双轨平衡匹配", "$2.18M", "1,842 笔", "k-binary", "var(--cyan)"),
                f5Kind("leadership", "LEADERSHIP", "领导奖池", "$1.46M", "214 笔", "k-leadership", "var(--warning)"),
                f5Kind("cultivation", "CULTIVATION", "培育奖 NEX", "$0.92M", "418 笔", "k-cultivation", "#B6A4FF"),
                f5Kind("genesis", "GENESIS", "创世节点二级版税", "$0.65M", "2,249 笔", "k-genesis", "var(--brand-2)"));
    }

    private static Map<String, Object> f5Kind(
            String key,
            String code,
            String label,
            String amountLabel,
            String countLabel,
            String className,
            String amountColor) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("code", code);
        item.put("label", label);
        item.put("amountLabel", amountLabel);
        item.put("countLabel", countLabel);
        item.put("className", className);
        item.put("amountColor", amountColor);
        return Collections.unmodifiableMap(item);
    }

    private static List<Map<String, Object>> f5CommissionFilters() {
        return List.of(
                f5Filter("all", "全部状态"),
                f5Filter("计提", "计提中"),
                f5Filter("可提", "已解锁可提"),
                f5Filter("frozen", "已冻结"),
                f5Filter("rejected", "已驳回"),
                f5Filter("异常回退", "异常回退"));
    }

    private static Map<String, Object> f5Filter(String key, String label) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        return Collections.unmodifiableMap(item);
    }

    private static List<Map<String, Object>> f5CommissionEvents() {
        return List.of(
                f5Event("CM-7781", "network", "usr_19C7", new BigDecimal("420"), "USDT", 60, "冷却 18d", "计提"),
                f5Event("CM-7780", "binary", "usr_31E8", new BigDecimal("1500"), "USDT", 100, "已解锁", "可提"),
                f5Event("CM-7779", "cultivation", "usr_02A9", new BigDecimal("200"), "NEX", 0, "冷却 30d", "计提"),
                f5Event("CM-7778", "leadership", "usr_77D4", new BigDecimal("1240"), "USDT", 100, "已解锁", "可提"),
                f5Event("CM-7777", "leadership", "usr_31E8", new BigDecimal("880"), "USDT", 100, "已解锁", "可提"),
                f5Event("CM-7776", "network", "usr_84F2", new BigDecimal("65"), "USDT", 42, "冷却 17d", "计提"),
                f5Event("CM-7775", "binary", "usr_84F2", new BigDecimal("490"), "USDT", 100, "已解锁", "可提"),
                f5Event("CM-7774", "genesis", "usr_19C7", new BigDecimal("90"), "USDT", 100, "已解锁", "可提"),
                f5Event("CM-7773", "cultivation", "usr_55B1", new BigDecimal("3500"), "NEX", 25, "冷却 22d", "计提"),
                f5Event("CM-7772", "leadership", "usr_19C7", new BigDecimal("520"), "USDT", 0, "冻结", "frozen"),
                f5Event("CM-7771", "network", "usr_02A9", new BigDecimal("140"), "USDT", 0, "已驳回", "rejected"),
                f5Event("CM-7770", "network", "usr_55B1", new BigDecimal("140"), "USDT", 0, "撤销", "异常回退"));
    }

    private static Map<String, Object> f5Event(
            String id,
            String kind,
            String user,
            BigDecimal amount,
            String currency,
            int cooldownPercent,
            String cooldownLabel,
            String state) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("kind", kind);
        item.put("user", user);
        item.put("amount", amount);
        item.put("currency", currency);
        item.put("cooldownPercent", cooldownPercent);
        item.put("cooldownLabel", cooldownLabel);
        item.put("state", state);
        return Collections.unmodifiableMap(item);
    }

    private static List<Map<String, Object>> f5CommissionAuditFeed() {
        return List.of(
                f5AuditFeed("2m", "CM-7770 网络版税异常回退已驳回,红冲 D4,risk-ops", "HIGH"),
                f5AuditFeed("14m", "CM-7762 培育奖 NEX 已冻结,K2 套利簇,risk-ops", "HIGH"),
                f5AuditFeed("38m", "CM-7747 领导奖提前解锁,周结算优先,super-admin", "MEDIUM"),
                f5AuditFeed("1h", "批量解锁 89 笔,双轨佣金冷却到期,server cron", "LOW"),
                f5AuditFeed("3h", "CM-7710 培育奖已驳回,红冲,risk-ops", "HIGH"));
    }

    private static Map<String, Object> f5AuditFeed(String when, String text, String level) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("when", when);
        item.put("text", text);
        item.put("level", level);
        return Collections.unmodifiableMap(item);
    }

    private record VRankSeed(
            String v,
            String selfBuy,
            String directRefs,
            String teamGv,
            String legCount,
            String legRank,
            int pop) {
    }

    private record F2MetricSeed(String id, String label, String value, String sub, String tone) {
    }

    private record F2UnilevelSeed(String level, BigDecimal usdtPct, BigDecimal nexReward, String label, boolean direct) {
    }

    private record F2RateTierSeed(String name, String requirement, String rate, String distribution, String className) {
    }

    private record F2PolicyParamSeed(
            String id,
            String name,
            String key,
            String defaultValue,
            String viewClass,
            String sub,
            boolean amplifies,
            boolean visualAmplify,
            String unit) {
    }

    private record F3MetricSeed(String id, String label, String value, String sub, String tone) {
    }

    private record F3BinarySettlementSeed(
            String user,
            int trackA,
            int trackB,
            int matchAmount,
            int todayPaid,
            String state,
            String tone) {
    }

    private record F4QuotaSeed(String name, int current, int cap, boolean tight) {
    }

    private record F4AmbassadorBandSeed(String name, int count) {
    }

    private record F4PodiumSeed(int rank, String userId, String gmvLabel, String tip, String className) {
    }
}
