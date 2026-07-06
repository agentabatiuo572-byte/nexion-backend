package ffdd.opsconsole.team.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.team.dto.VRankRewardRequest;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
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
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final List<String> VRANK_LEVELS = List.of(
            "V0", "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10", "V11", "V12");
    private static final List<VRankSeed> VRANK_SEEDS = List.of(
            vRank("V0"),
            vRank("V1", "selfBuy", "directRefs"),
            vRank("V2", "teamGv"),
            vRank("V3", "teamGv", "legCount", "legRank"),
            vRank("V4", "teamGv", "legCount", "legRank"),
            vRank("V5", "teamGv", "legCount", "legRank"),
            vRank("V6", "teamGv", "legCount", "legRank"),
            vRank("V7", "teamGv", "legCount", "legRank"),
            vRank("V8", "teamGv", "legCount", "legRank"),
            vRank("V9", "teamGv"),
            vRank("V10", "teamGv"),
            vRank("V11", "teamGv"),
            vRank("V12", "teamGv"));
    private static final Set<String> VRANK_REWARD_TYPES = Set.of("usdt", "nex", "voucher", "sku", "custom");
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
    private static final Set<String> UI_CONFIG_KEYS = uiConfigKeys();

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final AuditLogService auditLogService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final TeamFulfillmentQueueRepository fulfillmentQueueRepository;
    private final TeamCommissionRepository commissionRepository;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> binarySummary = binarySettlementSummary();
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
        response.put("binaryMaxTrackGmv", intValue(binarySummary.get("maxTrackGmv"), 0));
        response.put("binaryParticipantCount", intValue(binarySummary.get("participantCount"), 0));
        response.put("binaryMonthlyMatchedUsd", intValue(binarySummary.get("monthlyMatchedUsd"), 0));
        response.put("binaryAutoPlacement7dCount", intValue(binarySummary.get("autoPlacement7dCount"), 0));
        Map<String, Object> dailyCap = f3DailyCap();
        response.put("binaryDailyCapCurrentLabel", dailyCap.get("currentLabel"));
        response.put("binaryDailyCapWindowLabel", dailyCap.get("windowLabel"));
        response.put("binaryDailyCapNextTrigger", dailyCap.get("nextTrigger"));
        response.put("binaryDailyCapNextLabel", dailyCap.get("nextLabel"));
        response.put("leadershipPool", leadershipPoolReadModel());
        response.put("quotaPolicy", quotaPolicy());
        response.put("payoutGuardrails", guardrails());
        response.put("configValues", configValues());
        response.put("sunsetExclusions", sunsetExclusions());
        response.put("sources", List.of(
                "nx_config_item:team.* policy keys",
                "nx_config_item:team.ui.F.sunset.exclusions",
                "nx_v_rank_config",
                "nx_team_member",
                "nx_v_rank_reward_fulfillment",
                "nx_binary_commission_settlement",
                "nx_commission_event",
                "nx_team_hardware_quota_tier",
                "nx_team_hardware_quota_usage",
                "nx_team_ambassador_application",
                "nx_team_leaderboard_action",
                "B1 treasury coverage facade",
                "H1 growth rhythm facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> leadershipPool() {
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
                "nx_v_rank_config",
                "nx_team_member",
                "nx_team_hardware_quota_tier",
                "nx_team_hardware_quota_usage",
                "nx_team_ambassador_application",
                "nx_team_leaderboard_action",
                "nx_commission_event:leadership",
                "nx_config_item:team.ui.F.pool.ratio policy",
                "nx_config_item:team.ui.F.pool.monthlyCap policy",
                "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> commissions() {
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
                "nx_commission_event",
                "nx_wallet_ledger"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> ranks() {
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
        response.put("promotionWindowDays", configDecimal("team.rank_window_days", BigDecimal.ZERO).intValue());
        response.put("quotaPolicy", quotaPolicy());
        response.put("configValues", configValues());
        response.put("sources", List.of(
                "nx_v_rank_config",
                "nx_team_member",
                "nx_v_rank_reward_rule",
                "nx_commission_event:leadership"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> rates() {
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
                "nx_config_item:team.ui.F.influence.*",
                "nx_commission_rule:UNILEVEL",
                "nx_v_rank_config",
                "nx_team_member",
                "nx_commission_event",
                "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> binary() {
        Map<String, Object> summary = binarySettlementSummary();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F3");
        response.put("metrics", f3Metrics(summary));
        response.put("formula", f3Formula());
        response.put("settlements", binarySettlements());
        response.put("maxTrackGmv", intValue(summary.get("maxTrackGmv"), 0));
        response.put("participantCount", intValue(summary.get("participantCount"), 0));
        response.put("blockedCount", intValue(summary.get("blockedCount"), 0));
        response.put("monthlyMatchedUsd", intValue(summary.get("monthlyMatchedUsd"), 0));
        response.put("autoPlacement7dCount", intValue(summary.get("autoPlacement7dCount"), 0));
        response.put("dailyMatchUsd", intValue(summary.get("dailyMatchUsd"), 0));
        response.put("dailyCap", f3DailyCap());
        response.put("config", f3Config(summary));
        response.put("commissionPolicy", commissionPolicy());
        response.put("guardrails", guardrails());
        response.put("configValues", configValues());
        response.put("sources", List.of(
                "nx_binary_commission_settlement",
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
        VRankSeed seed = requireVRank(rank);
        String normalizedField = requireText(field, "TEAM_VRANK_FIELD_REQUIRED");
        if (!thresholdFields(seed).contains(normalizedField)) {
            throw new IllegalArgumentException("Unsupported F1 V-Rank threshold field");
        }
        String value = normalizeVRankThreshold(normalizedField, request.value());
        Object businessValue = vRankThresholdBusinessValue(normalizedField, value);
        Map<String, Object> before = vrankRows().stream()
                .filter(row -> seed.v().equals(row.get("v")))
                .findFirst()
                .orElse(Map.of());
        String oldValue = String.valueOf(before.getOrDefault(normalizedField, ""));
        if (!commissionRepository.updateVRankThreshold(seed.v(), normalizedField, businessValue)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "VRANK_BUSINESS_TABLE_UPDATE_FAILED");
        }
        String resourceId = "nx_v_rank_config:" + seed.v() + "." + normalizedField;
        audit("F_TEAM_VRANK_THRESHOLD_CHANGED", resourceId, request.operator(), Map.of(
                "rank", seed.v(),
                "field", normalizedField,
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = ranks().getData();
        response.put("updated", Map.of("key", seed.v() + "." + normalizedField, "source", "nx_v_rank_config", "oldValue", oldValue, "newValue", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> addVRankReward(String rank, String idempotencyKey, VRankRewardRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        VRankSeed seed = requireVRank(rank);
        Map<String, Object> item = normalizeReward(nextRewardId(seed.v()), request);
        if (!commissionRepository.addVRankReward(seed.v(), item)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "VRANK_REWARD_BUSINESS_TABLE_INSERT_FAILED");
        }
        audit("F_TEAM_VRANK_REWARD_ADDED", rewardResourceId(seed.v(), String.valueOf(item.get("id"))), request.operator(), Map.of(
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
        VRankSeed seed = requireVRank(rank);
        String id = requireText(rewardId, "TEAM_VRANK_REWARD_ID_REQUIRED");
        boolean exists = activeRewards(seed.v()).stream()
                .anyMatch(item -> id.equals(String.valueOf(item.get("id"))));
        if (!exists) {
            throw new IllegalArgumentException("F1 V-Rank reward not found");
        }
        Map<String, Object> item = normalizeReward(id, request);
        if (!commissionRepository.updateVRankReward(seed.v(), id, item)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "VRANK_REWARD_BUSINESS_TABLE_UPDATE_FAILED");
        }
        audit("F_TEAM_VRANK_REWARD_CHANGED", rewardResourceId(seed.v(), id), request.operator(), Map.of(
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
        VRankSeed seed = requireVRank(rank);
        String id = requireText(rewardId, "TEAM_VRANK_REWARD_ID_REQUIRED");
        boolean exists = activeRewards(seed.v()).stream()
                .anyMatch(item -> id.equals(String.valueOf(item.get("id"))));
        if (!exists) {
            throw new IllegalArgumentException("F1 V-Rank reward not found");
        }
        if (!commissionRepository.deleteVRankReward(seed.v(), id)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "VRANK_REWARD_BUSINESS_TABLE_DELETE_FAILED");
        }
        audit("F_TEAM_VRANK_REWARD_REMOVED", rewardResourceId(seed.v(), id), request.operator(), Map.of(
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
        Optional<String> commissionEventId = commissionStatusEventId(key);
        if (commissionEventId.isPresent()) {
            return updateCommissionEventStatus(idempotencyKey, request, key, commissionEventId.get());
        }
        if (isUnilevelRuleKey(key)) {
            return updateUnilevelRule(idempotencyKey, request, key);
        }
        if (!UI_CONFIG_KEYS.contains(key)) {
            throw new IllegalArgumentException("Unsupported F team UI config key");
        }
        String value = normalizeUiValue(request.value());
        validateUiConfig(key, value);
        String configKey = uiConfigKey(key);
        String oldValue = configFacade.activeValue(configKey).orElse("");
        configFacade.upsertAdminValue(configKey, value, "TEXT", "team", "F domain UI-backed policy state");
        postCommissionLedgerIfStatusChanged(key, value);
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

    private ApiResult<Map<String, Object>> updateUnilevelRule(
            String idempotencyKey,
            TeamCommissionConfigUpdateRequest request,
            String key) {
        String value = normalizeUiValue(request.value());
        int layerNo = unilevelLayerNo(key);
        String field = key.startsWith("F.unilevel.nex.") ? "nexPerUsd" : "usdtRate";
        Object businessValue = "usdtRate".equals(field)
                ? percentRatio(value, BigDecimal.ZERO)
                : parseDecimal(value, BigDecimal.ZERO);
        Map<String, Object> oldRow = unilevelRates().stream()
                .filter(row -> ("L" + layerNo).equals(row.get("level")))
                .findFirst()
                .orElse(Map.of());
        String oldValue = String.valueOf(oldRow.getOrDefault("usdtRate".equals(field) ? "usdtPct" : "nexReward", ""));
        if (!commissionRepository.updateUnilevelRule(layerNo, field, businessValue)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "UNILEVEL_RULE_BUSINESS_TABLE_UPDATE_FAILED");
        }
        String resourceId = "nx_commission_rule:UNILEVEL:L" + layerNo + "." + field;
        audit("F_TEAM_UNILEVEL_RULE_CHANGED", resourceId, request.operator(), Map.of(
                "key", key,
                "field", field,
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = rates().getData();
        response.put("updated", Map.of("key", key, "source", "nx_commission_rule", "oldValue", oldValue, "newValue", value));
        return ApiResult.ok(response);
    }

    private ApiResult<Map<String, Object>> updateCommissionEventStatus(
            String idempotencyKey,
            TeamCommissionConfigUpdateRequest request,
            String key,
            String eventId) {
        String value = normalizeUiValue(request.value());
        Map<String, Object> oldEvent = commissionEvents().stream()
                .filter(row -> eventId.equals(row.get("id")))
                .findFirst()
                .orElse(null);
        if (oldEvent == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMMISSION_EVENT_NOT_FOUND");
        }
        if (!commissionRepository.updateCommissionStatus(eventId, value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMMISSION_EVENT_UPDATE_FAILED");
        }
        postCommissionLedgerIfStatusChanged(key, value);
        audit("F_TEAM_COMMISSION_STATUS_CHANGED", "nx_commission_event:" + eventId, request.operator(), Map.of(
                "key", key,
                "eventId", eventId,
                "oldValue", oldEvent.get("state"),
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = overview().getData();
        response.put("updated", Map.of("key", key, "source", "nx_commission_event", "oldValue", oldEvent.get("state"), "newValue", value));
        return ApiResult.ok(response);
    }

    private void postCommissionLedgerIfStatusChanged(String key, String value) {
        Optional<String> eventId = commissionStatusEventId(key);
        if (eventId.isEmpty()) {
            return;
        }
        Map<String, Object> event = commissionEvents().stream()
                .filter(row -> eventId.get().equals(row.get("id")))
                .findFirst()
                .orElse(null);
        if (event == null) {
            return;
        }
        BigDecimal amount = decimalValue(event.get("amount"), BigDecimal.ZERO);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String state = normalizeUiValue(value);
        ledgerPostingFacade.postLedgerEntry(
                "F5-COMMISSION-" + eventId.get() + "-" + ledgerStateCode(state),
                userIdFromText(String.valueOf(event.get("user"))),
                "TEAM_COMMISSION",
                String.valueOf(event.getOrDefault("currency", "USDT")),
                commissionLedgerDirection(state),
                amount,
                commissionLedgerStatus(state),
                "F5 commission status disposition | eventId=" + eventId.get() + " | state=" + state);
    }

    private Optional<String> commissionStatusEventId(String key) {
        String prefix = "F.commission.";
        String suffix = ".status";
        if (!key.startsWith(prefix) || !key.endsWith(suffix)) {
            return Optional.empty();
        }
        return Optional.of(key.substring(prefix.length(), key.length() - suffix.length()));
    }

    private boolean isUnilevelRuleKey(String key) {
        if (!key.startsWith("F.unilevel.L") && !key.startsWith("F.unilevel.nex.L")) {
            return false;
        }
        String raw = key.startsWith("F.unilevel.nex.L")
                ? key.substring("F.unilevel.nex.L".length())
                : key.substring("F.unilevel.L".length());
        return StringUtils.hasText(raw) && raw.chars().allMatch(Character::isDigit);
    }

    private int unilevelLayerNo(String key) {
        String raw = key.startsWith("F.unilevel.nex.L")
                ? key.substring("F.unilevel.nex.L".length())
                : key.substring("F.unilevel.L".length());
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw new IllegalArgumentException("Unsupported F2 unilevel layer");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unsupported F2 unilevel layer", ex);
        }
    }

    private String ledgerStateCode(String state) {
        return normalizeUiValue(state).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private String commissionLedgerDirection(String state) {
        String normalized = normalizeUiValue(state).toLowerCase(Locale.ROOT);
        return "unlocked".equals(normalized) || normalized.contains("可提") ? "IN" : "OUT";
    }

    private String commissionLedgerStatus(String state) {
        String normalized = normalizeUiValue(state).toLowerCase(Locale.ROOT);
        return "unlocked".equals(normalized) || normalized.contains("可提") ? "PENDING" : "SUCCESS";
    }

    private Long userIdFromText(String value) {
        if (!StringUtils.hasText(value)) {
            return 0L;
        }
        String digits = value.replaceAll("\\D+", "");
        if (!StringUtils.hasText(digits)) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Map<String, Object> commissionPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("directRoyaltyPct", percent(configDecimal("team.direct_royalty_pct", BigDecimal.ZERO)));
        policy.put("networkRoyaltyPct", percent(configDecimal("team.network_royalty_pct", BigDecimal.ZERO)));
        policy.put("binaryPairRatePct", percent(configDecimal("team.binary_pair_rate_pct", BigDecimal.ZERO)));
        policy.put("maxCombinedOutflowPct", percent(configDecimal("team.max_combined_outflow_pct", BigDecimal.ZERO)));
        policy.put("minPayoutUsdt", configDecimal("team.min_payout_usdt", BigDecimal.ZERO));
        policy.put("settlementStatus", configText("F.settlement.status", ""));
        return policy;
    }

    private List<Map<String, Object>> rankLadder() {
        return List.of();
    }

    private List<Map<String, Object>> vrankRows() {
        return commissionRepository.vRankRows().stream()
                .map(this::normalizeVRankRow)
                .toList();
    }

    private Map<String, Object> normalizeVRankRow(Map<String, Object> raw) {
        String level = textValue(raw, "v", "");
        // 按 VRANK_SEEDS 字段组合输出:组合外的门槛字段 put null,前端 fieldsOf 按 != null 显示按钮,
        // 避免非该阶字段(如非 V1 的 selfBuy)误显示导致编辑时 thresholdFields 校验报 Unsupported。
        VRankSeed seed = VRANK_SEEDS.stream().filter(s -> s.v().equals(level)).findFirst().orElse(null);
        Set<String> fields = seed != null ? seed.fields() : Set.of();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", level);
        row.put("label", textValue(raw, "label", level));
        row.put("selfBuy", fields.contains("selfBuy") ? moneyCompact(intValue(raw.get("selfBuyUsd"), 0)) : null);
        row.put("directRefs", fields.contains("directRefs") ? intValue(raw.get("directRefs"), 0) : null);
        row.put("teamGv", fields.contains("teamGv") ? moneyCompact(intValue(raw.get("teamGvUsd"), 0)) : null);
        row.put("legCount", fields.contains("legCount") ? intValue(raw.get("legCount"), 0) : null);
        row.put("legRank", fields.contains("legRank") ? textValue(raw, "legRank", "") : null);
        row.put("votes", intValue(raw.get("votes"), 0));
        row.put("pop", intValue(raw.get("pop"), 0));
        row.put("rewards", activeRewards(level));
        row.put("source", "nx_v_rank_config + nx_team_member");
        return row;
    }

    private List<Map<String, Object>> fulfillmentQueues() {
        return fulfillmentQueueRepository.fulfillmentQueues().stream()
                .map(this::fulfillmentQueue)
                .toList();
    }

    private Map<String, Object> fulfillmentQueue(TeamFulfillmentQueueRow source) {
        String rankCode = normalizeUiValue(source.rankCode());
        String rewardName = normalizeUiValue(source.rewardName());
        String status = normalizeUiValue(source.status());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", rankCode);
        row.put("name", rewardName);
        row.put("count", source.count() == null ? 0 : source.count());
        row.put("status", status);
        row.put("configKey", StringUtils.hasText(rankCode) ? "F.fulfillment." + rankCode + ".queue.status" : "");
        row.put("source", "nx_v_rank_reward_fulfillment");
        return row;
    }

    private List<Map<String, Object>> f2Metrics() {
        return commissionRepository.f2Metrics().stream()
                .map(row -> {
                    Map<String, Object> metric = new LinkedHashMap<>();
                    metric.put("id", textValue(row, "id", ""));
                    metric.put("label", textValue(row, "label", ""));
                    metric.put("value", textValue(row, "value", ""));
                    metric.put("sub", textValue(row, "sub", ""));
                    metric.put("tone", textValue(row, "tone", ""));
                    metric.put("source", "nx_commission_event / nx_team_member");
                    return metric;
                })
                .toList();
    }

    private List<Map<String, Object>> unilevelRates() {
        return commissionRepository.unilevelRates().stream()
                .map(this::normalizeUnilevelRate)
                .toList();
    }

    private Map<String, Object> normalizeUnilevelRate(Map<String, Object> raw) {
        String level = textValue(raw, "level", "");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("level", level);
        row.put("usdtPct", decimalValue(raw.get("usdtPct"), BigDecimal.ZERO).stripTrailingZeros());
        row.put("nexReward", decimalValue(raw.get("nexReward"), BigDecimal.ZERO).stripTrailingZeros());
        row.put("label", textValue(raw, "label", ""));
        row.put("direct", boolValue(raw.get("direct"), false));
        row.put("configKey", "nx_commission_rule:UNILEVEL:" + level + ".usdt_rate");
        row.put("nexConfigKey", "nx_commission_rule:UNILEVEL:" + level + ".nex_per_usd");
        row.put("source", "nx_commission_rule");
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
        row.put("value", configFacade.activeValue(uiConfigKey(seed.key()))
                .orElse(""));
        row.put("defaultValue", "");
        row.put("viewClass", seed.viewClass());
        row.put("sub", seed.sub());
        row.put("amplifies", seed.amplifies());
        row.put("visualAmplify", seed.visualAmplify());
        row.put("unit", seed.unit());
        return row;
    }

    private Map<String, Object> quotaPolicy() {
        return Map.of(
                "hardwareQuotaPerRank", configDecimal("team.hardware_quota_per_rank", BigDecimal.ZERO).intValue(),
                "quotaOwner", "F5",
                "deviceReleasePacing", "mirrors H1 but does not own H1 phase dials",
                "b1PrecheckOnLoosening", true);
    }

    private List<Map<String, Object>> rateTiers() {
        return commissionRepository.rateTiers().stream()
                .map(this::normalizeRateTier)
                .toList();
    }

    private Map<String, Object> normalizeRateTier(Map<String, Object> raw) {
        String rate = textValue(raw, "rate", "0%");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", textValue(raw, "name", ""));
        row.put("requirement", textValue(raw, "requirement", ""));
        row.put("rate", rate);
        row.put("ratePct", parseDecimal(rate, BigDecimal.ZERO).stripTrailingZeros());
        row.put("distribution", textValue(raw, "distribution", "0%"));
        row.put("className", textValue(raw, "className", ""));
        row.put("source", "nx_v_rank_config + nx_team_member");
        return row;
    }

    private List<Map<String, Object>> f3Metrics(Map<String, Object> summary) {
        if (summary.isEmpty() || intValue(summary.get("participantCount"), 0) == 0) {
            return List.of();
        }
        return List.of(
                f3Metric("todayBalanceMatch", "今日 Balance Match", moneyCompact(intValue(summary.get("dailyMatchUsd"), 0)),
                        intValue(summary.get("participantCount"), 0) + " 用户参与结算", "ok"),
                f3Metric("participantCount", "参与结算用户", String.valueOf(intValue(summary.get("participantCount"), 0)),
                        "两轨均达到结算门槛", ""),
                f3Metric("blockedUsers", "阻塞用户(轨不平衡)", String.valueOf(intValue(summary.get("blockedCount"), 0)),
                        "来自结算状态聚合", intValue(summary.get("blockedCount"), 0) > 0 ? "warn" : ""),
                f3Metric("residualPool", "沉淀池(未匹配)", moneyCompact(intValue(summary.get("residualPoolUsd"), 0)),
                        "来自左右轨未匹配差额", "cyan"));
    }

    private Map<String, Object> f3Metric(String id, String label, String value, String sub, String tone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("label", label);
        row.put("value", value);
        row.put("sub", sub);
        row.put("tone", tone);
        row.put("source", "nx_binary_commission_settlement");
        return row;
    }

    private Map<String, Object> f3Formula() {
        Map<String, Object> settlement = binarySettlements().stream().findFirst().orElse(Map.of());
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("user", String.valueOf(settlement.getOrDefault("user", "")));
        formula.put("trackA", intValue(settlement.get("trackA"), 0));
        formula.put("trackB", intValue(settlement.get("trackB"), 0));
        formula.put("matchAmount", intValue(settlement.get("matchAmount"), 0));
        formula.put("matchRate", configText("F.binary.matchRate", ""));
        formula.put("threshold", configText("F.binary.threshold", ""));
        formula.put("settlePeriod", configText("F.binary.settlePeriod", ""));
        return formula;
    }

    private Map<String, Object> f3Config(Map<String, Object> summary) {
        String spillover = configText("F.binary.spillover", "");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("threshold", configText("F.binary.threshold", ""));
        config.put("matchRate", configText("F.binary.matchRate", ""));
        config.put("spillover", spillover);
        config.put("spilloverEnabled", !"已关闭".equals(spillover));
        config.put("gvResetCron", configText("F.binary.gvResetCron", ""));
        config.put("settlePeriod", configText("F.binary.settlePeriod", ""));
        config.put("residualPolicy", configText("F.binary.residualPolicy", ""));
        config.put("residualPool", moneyCompact(intValue(summary.get("residualPoolUsd"), 0)));
        config.put("residualSub", "来自 nx_binary_commission_settlement");
        return config;
    }

    private Map<String, Object> f3DailyCap() {
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("currentLabel", configText("F3.dailyCap.currentLabel", ""));
        cap.put("windowLabel", configText("F3.dailyCap.windowLabel", ""));
        cap.put("nextTrigger", configText("F3.dailyCap.nextTrigger", ""));
        cap.put("nextLabel", configText("F3.dailyCap.nextLabel", ""));
        cap.put("currentMonth", rhythm.currentMonth());
        cap.put("currentPhase", rhythm.currentPhase());
        cap.put("h1CommissionTighteningPct", rhythm.commissionTighteningPct());
        cap.put("h1Rhythm", rhythm.summary());
        return cap;
    }

    private List<Map<String, Object>> binarySettlements() {
        return commissionRepository.binarySettlements(100).stream()
                .map(this::normalizeBinarySettlementMap)
                .toList();
    }

    private Map<String, Object> binarySettlementSummary() {
        Map<String, Object> row = new LinkedHashMap<>(commissionRepository.binarySettlementSummary());
        row.putIfAbsent("autoPlacement7dCount", 0);
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
                .orElse(0);
    }

    private Map<String, Object> leadershipPoolReadModel() {
        String ratioLabel = configText("F.pool.ratio", "");
        BigDecimal poolRatio = percentRatio(ratioLabel, BigDecimal.ZERO);
        Map<String, Object> summary = commissionRepository.leadershipPoolSummary();
        int weeklyGmvUsd = intValue(summary.get("weeklyGmvUsd"), 0);
        int weeklyInjectedUsd = intValue(summary.get("weeklyInjectedUsd"), 0);
        int unlockRank = leadershipUnlockRank();
        int topN = intConfig(uiConfigKey("F.vrank.leadership.topN"), 0);
        List<Map<String, Object>> ranks = leadershipRanks();
        List<Map<String, Object>> quotaRows = quotaRows();
        int quotaCurrent = quotaRows.stream().mapToInt(row -> intValue(row.get("current"), 0)).sum();
        int quotaCap = quotaRows.stream().mapToInt(row -> intValue(row.get("cap"), 0)).sum();
        Map<String, Object> ambassador = commissionRepository.ambassadorSummary();
        Map<String, Object> leaderboard = commissionRepository.leaderboardSummary();
        String leaderboardStatus = textValue(leaderboard, "periodStatus", "");
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("weeklyInjectedUsd", weeklyInjectedUsd);
        pool.put("weeklyGmvUsd", weeklyGmvUsd);
        pool.put("poolRatio", ratioLabel);
        pool.put("poolRatioValue", poolRatio);
        pool.put("monthlyCapLabel", configText("F.pool.monthlyCap", ""));
        pool.put("monthlyCapUsd", moneyLabelToInt(configText("F.pool.monthlyCap", ""), 0));
        pool.put("monthLeadershipUsd", intValue(summary.get("monthLeadershipUsd"), 0));
        pool.put("participantCount", intValue(summary.get("participantCount"), 0));
        pool.put("topN", topN);
        pool.put("topSharePct", leadershipTopConcentrationPct(ranks, topN));
        pool.put("unlockRank", unlockRank);
        pool.put("settlementWindow", "");
        pool.put("settlementDispatchWindow", "");
        pool.put("quotaRows", quotaRows);
        pool.put("quotaMonthlyStockLabel", String.valueOf(quotaCap));
        pool.put("quotaMonthlyStockTotal", quotaCap);
        pool.put("quotaMonthlyStockUsed", quotaCurrent);
        pool.put("quotaMonthlyStockRemaining", Math.max(0, quotaCap - quotaCurrent));
        pool.put("proUnlock", quotaUnlock("PRO"));
        pool.put("rackUnlock", quotaUnlock("RACK"));
        pool.put("ambassadorBands", ambassadorBands());
        pool.put("ambassadorStatus", textValue(ambassador, "status", ""));
        pool.put("ambassadorPendingCount", intValue(ambassador.get("pendingCount"), 0));
        pool.put("ambassadorBudgetApprovedLabel", moneyLabel(decimalValue(ambassador.get("approvedBudgetUsd"), BigDecimal.ZERO)));
        pool.put("ambassadorBudgetCapLabel", moneyLabel(decimalValue(ambassador.get("requestedBudgetUsd"), BigDecimal.ZERO)));
        pool.put("ambassadorKolBudgetPct", intValue(ambassador.get("kolBudgetPct"), 0));
        pool.put("ambassadorNextQuotaReviewDate", textValue(ambassador, "nextQuotaReviewDate", ""));
        pool.put("leaderboardPoolLabel", moneyLabel(decimalValue(leaderboard.get("poolUsd"), BigDecimal.ZERO)));
        pool.put("leaderboardParticipantCount", intValue(leaderboard.get("participantCount"), 0));
        pool.put("leaderboardFraudHitCount", intValue(leaderboard.get("fraudHitCount"), 0));
        pool.put("leaderboardDisqualified", Set.of("disqualified", "flagged").contains(leaderboardStatus.toLowerCase(Locale.ROOT)));
        pool.put("leaderboardPeriodStatus", leaderboardStatus);
        pool.put("podium", leaderboardPodium());
        pool.put("voteWeights", voteWeights());
        return pool;
    }

    private List<Map<String, Object>> f4Metrics(Map<String, Object> pool) {
        if (intValue(pool.get("weeklyGmvUsd"), 0) == 0
                && intValue(pool.get("weeklyInjectedUsd"), 0) == 0
                && pool.get("quotaRows") instanceof List<?> quotaRows
                && quotaRows.isEmpty()) {
            return List.of();
        }
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
        return commissionRepository.quotaRows().stream()
                .map(this::normalizeQuotaRow)
                .toList();
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
        return commissionRepository.ambassadorBands().stream()
                .map(this::normalizeAmbassadorBand)
                .toList();
    }

    private Map<String, Object> normalizeAmbassadorBand(Map<String, Object> raw) {
        return Map.of(
                "name", String.valueOf(raw.getOrDefault("name", "BAND")),
                "count", intValue(raw.get("count"), 0));
    }

    private List<Map<String, Object>> leaderboardPodium() {
        return commissionRepository.leaderboardPodium(3).stream()
                .map(this::normalizePodium)
                .toList();
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
        return leadershipRanks().stream()
                .map(row -> {
                    Map<String, Object> weight = new LinkedHashMap<>();
                    weight.put("v", "V" + intValue(row.get("v"), 0));
                    weight.put("votes", decimalValue(row.get("votes"), BigDecimal.ZERO));
                    weight.put("source", "nx_v_rank_config");
                    return weight;
                })
                .toList();
    }

    private Map<String, Object> commissionSummary(List<Map<String, Object>> commissionEvents) {
        long abnormalOrFrozen = commissionEvents.stream()
                .map(event -> String.valueOf(event.get("state")))
                .filter(state -> "异常回退".equals(state) || "frozen".equals(state) || "rejected".equals(state))
                .count();
        BigDecimal total = sumCommissionAmount(commissionEvents);
        BigDecimal cooling = sumCommissionAmountByState(commissionEvents, "计提");
        BigDecimal withdrawable = sumCommissionAmountByState(commissionEvents, "可提", "unlocked");
        return Map.of(
                "monthlyCommissionSpendLabel", moneyLabel(total),
                "coolingBalanceLabel", moneyLabel(cooling),
                "withdrawableThisMonthLabel", moneyLabel(withdrawable),
                "abnormalOrFrozenCount", abnormalOrFrozen);
    }

    private BigDecimal sumCommissionAmount(List<Map<String, Object>> commissionEvents) {
        return commissionEvents.stream()
                .map(event -> decimalValue(event.get("amount"), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCommissionAmountByState(List<Map<String, Object>> commissionEvents, String... states) {
        Set<String> expected = Set.of(states);
        return commissionEvents.stream()
                .filter(event -> expected.contains(String.valueOf(event.get("state"))))
                .map(event -> decimalValue(event.get("amount"), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String moneyLabel(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return "$" + safe.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private List<Map<String, Object>> commissionKinds() {
        return commissionRepository.commissionKindSummary().stream()
                .map(this::normalizeCommissionKind)
                .toList();
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
                Map.of("key", "withdrawable", "label", "可提"),
                Map.of("key", "cooling", "label", "计提"),
                Map.of("key", "exception", "label", "异常/冻结/驳回"));
    }

    private List<Map<String, Object>> commissionEvents() {
        return commissionRepository.commissionEvents(100).stream()
                .map(this::normalizeCommissionEvent)
                .toList();
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
        String state = fallbackState;
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
        return commissionRepository.commissionAuditFeed(20).stream()
                .map(this::normalizeCommissionAuditFeedItem)
                .toList();
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
            case "directRoyaltyPct" -> configDecimal("team.direct_royalty_pct", BigDecimal.ZERO);
            case "networkRoyaltyPct" -> configDecimal("team.network_royalty_pct", BigDecimal.ZERO);
            case "binaryPairRatePct" -> configDecimal("team.binary_pair_rate_pct", BigDecimal.ZERO);
            case "maxCombinedOutflowPct" -> configDecimal("team.max_combined_outflow_pct", BigDecimal.ZERO);
            case "minPayoutUsdt" -> configDecimal("team.min_payout_usdt", BigDecimal.ZERO);
            case "rankWindowDays" -> configDecimal("team.rank_window_days", BigDecimal.ZERO);
            case "hardwareQuotaPerRank" -> configDecimal("team.hardware_quota_per_rank", BigDecimal.ZERO);
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

    private static final ObjectMapper JSON_PROBE = new ObjectMapper();

    private String normalizeUiValue(String raw) {
        String value = requireText(raw, "TEAM_CONFIG_VALUE_REQUIRED");
        String trimmed = value.trim();
        boolean isJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        // JSON 类配置(头衔/Partner Status/PERIOD_PRIZE/大使预算等)放宽到 4000 字符;标量保持 160。
        int limit = isJson ? 4000 : 160;
        if (trimmed.length() > limit) {
            throw new IllegalArgumentException("F team UI config value is too long");
        }
        if (isJson && !isValidJson(trimmed)) {
            throw new IllegalArgumentException("F team UI config JSON is malformed");
        }
        return trimmed;
    }

    private boolean isValidJson(String raw) {
        try {
            JSON_PROBE.readTree(raw);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // key-specific 校验:开关 / 数字范围 / JSON schema。批1a 文本项(titles/prize.name/settleCron/unlockVRank)不强校验。
    private void validateUiConfig(String key, String value) {
        switch (key) {
            case "F.vrank.permanent", "F.leaderboard.paused", "F.binary.paused" -> {
                if (!"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("F_TEAM_TOGGLE_INVALID");
                }
            }
            case "F.leaderboard.minUsd" -> {
                if (parseDecimal(value, BigDecimal.valueOf(-1)).signum() < 0) {
                    throw new IllegalArgumentException("F_TEAM_NUMBER_INVALID");
                }
            }
            case "F.unilevel.depth" -> {
                int depth;
                try {
                    depth = Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("F_TEAM_NUMBER_INVALID");
                }
                if (depth < 1 || depth > 10) {
                    throw new IllegalArgumentException("F_TEAM_DEPTH_OUT_OF_RANGE");
                }
            }
            case "F.commission.anomalyThreshold" -> validateAnomalyThreshold(value);
            case "F.pool.top1MaxPct", "F.pool.top5MaxPct" -> {
                var pct = parseDecimal(value, BigDecimal.valueOf(-1));
                if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new IllegalArgumentException("F_TEAM_PCT_OUT_OF_RANGE");
                }
            }
            case "F.pool.periodPrize" -> validatePeriodPrize(value);
            case "F.partner.tiers" -> validatePartnerTiers(value);
            case "F.vrank.titles" -> validateVrankTitles(value);
            default -> {
                // F.unilevel.L{1-7}.paused 动态 toggle(7 层独立暂停开关);非该 pattern 的 key 白名单已过滤。
                if (key.matches("F\\.unilevel\\.L[1-7]\\.paused")
                        && !"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("F_TEAM_TOGGLE_INVALID");
                }
            }
        }
    }

    private void validateAnomalyThreshold(String value) {
        try {
            var node = JSON_PROBE.readTree(value);
            var frozen = node.get("frozen");
            var anomaly = node.get("anomaly");
            if (!node.isObject() || frozen == null || anomaly == null || !frozen.isNumber() || !anomaly.isNumber()) {
                throw new IllegalArgumentException("F_ANOMALY_THRESHOLD_SCHEMA_INVALID");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("F_ANOMALY_THRESHOLD_SCHEMA_INVALID");
        }
    }

    // 4 周期榜单奖池(today/week/month/allTime)· 各须为非负数字。
    private void validatePeriodPrize(String value) {
        try {
            var node = JSON_PROBE.readTree(value);
            if (!node.isObject()) {
                throw new IllegalArgumentException("F_PERIOD_PRIZE_SCHEMA_INVALID");
            }
            for (String period : new String[]{"today", "week", "month", "allTime"}) {
                var amount = node.get(period);
                if (amount == null || !amount.isNumber() || amount.decimalValue().signum() < 0) {
                    throw new IllegalArgumentException("F_PERIOD_PRIZE_SCHEMA_INVALID");
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("F_PERIOD_PRIZE_SCHEMA_INVALID");
        }
    }

    // Partner Status 4 档门槛(bronze/silver/gold/diamond)· 须为数字且非递减。
    private void validatePartnerTiers(String value) {
        try {
            var node = JSON_PROBE.readTree(value);
            if (!node.isObject()) {
                throw new IllegalArgumentException("F_PARTNER_TIERS_SCHEMA_INVALID");
            }
            var bronze = node.get("bronze");
            var silver = node.get("silver");
            var gold = node.get("gold");
            var diamond = node.get("diamond");
            if (bronze == null || silver == null || gold == null || diamond == null
                    || !bronze.isNumber() || !silver.isNumber() || !gold.isNumber() || !diamond.isNumber()) {
                throw new IllegalArgumentException("F_PARTNER_TIERS_SCHEMA_INVALID");
            }
            if (bronze.decimalValue().compareTo(silver.decimalValue()) > 0
                    || silver.decimalValue().compareTo(gold.decimalValue()) > 0
                    || gold.decimalValue().compareTo(diamond.decimalValue()) > 0) {
                throw new IllegalArgumentException("F_PARTNER_TIERS_NOT_ASCENDING");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("F_PARTNER_TIERS_SCHEMA_INVALID");
        }
    }

    // V-Rank 13 阶头衔(V0-V12)· 每阶须为非空文本。
    private void validateVrankTitles(String value) {
        try {
            var node = JSON_PROBE.readTree(value);
            if (!node.isObject()) {
                throw new IllegalArgumentException("F_VRANK_TITLES_SCHEMA_INVALID");
            }
            for (String level : VRANK_LEVELS) {
                var title = node.get(level);
                if (title == null || !title.isTextual() || title.asText().isBlank()) {
                    throw new IllegalArgumentException("F_VRANK_TITLES_SCHEMA_INVALID");
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("F_VRANK_TITLES_SCHEMA_INVALID");
        }
    }

    private Map<String, String> configValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : UI_CONFIG_KEYS) {
            configFacade.activeValue(uiConfigKey(key)).ifPresent(value -> values.put(key, value));
        }
        return values;
    }

    private List<String> sunsetExclusions() {
        Optional<String> raw = configFacade.activeValue(uiConfigKey("F.sunset.exclusions"));
        if (raw.isEmpty()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String item : raw.get().split("[,;，；\\r\\n]+")) {
            if (StringUtils.hasText(item)) {
                values.add(item.trim());
            }
        }
        return new ArrayList<>(values);
    }

    private Map<String, List<Map<String, Object>>> vRankRewards() {
        Map<String, List<Map<String, Object>>> rewards = new LinkedHashMap<>();
        for (String level : VRANK_LEVELS) {
            rewards.put(level, activeRewards(level));
        }
        return rewards;
    }

    private List<Map<String, Object>> activeRewards(String level) {
        return commissionRepository.vRankRewards(level).stream()
                .map(this::normalizeRewardMap)
                .toList();
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

    private String rewardResourceId(String level, String rewardId) {
        return "nx_v_rank_reward_rule:" + level + ":" + rewardId;
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

    private String nextRewardId(String level) {
        return "vr-" + level + "-" + UUID.randomUUID().toString().substring(0, 8);
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
        Map<String, Object> summary = commissionRepository.leadershipPoolSummary();
        Map<String, Object> leaderboard = commissionRepository.leaderboardSummary();
        int unlockRank = leadershipUnlockRank();
        int topN = intConfig(uiConfigKey("F.vrank.leadership.topN"), 0);
        List<Map<String, Object>> ranks = leadershipRanks();
        int totalMembers = ranks.stream().mapToInt(rank -> ((Number) rank.get("pop")).intValue()).sum();
        int qualifiers = ranks.stream()
                .filter(rank -> ((Number) rank.get("v")).intValue() >= unlockRank)
                .mapToInt(rank -> ((Number) rank.get("pop")).intValue())
                .sum();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("weeklyGmvUsdt", decimalValue(summary.get("weeklyGmvUsd"), BigDecimal.ZERO));
        response.put("poolRatio", percentRatio(configText("F.pool.ratio", ""), BigDecimal.ZERO));
        response.put("monthlyCapUsdt", decimalValue(leaderboard.get("poolUsd"), BigDecimal.ZERO));
        response.put("unlockRank", unlockRank);
        response.put("topN", topN);
        response.put("ranks", ranks);
        response.put("topConcentrationPct", leadershipTopConcentrationPct(ranks, topN));
        response.put("qualifiers", qualifiers);
        response.put("totalMembers", totalMembers);
        return response;
    }

    private List<Map<String, Object>> leadershipRanks() {
        return commissionRepository.leadershipRanks().stream()
                .map(row -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("v", intValue(row.get("v"), 0));
                    normalized.put("votes", intValue(row.get("votes"), 0));
                    normalized.put("pop", intValue(row.get("pop"), 0));
                    normalized.put("source", "nx_v_rank_config + nx_team_member");
                    return normalized;
                })
                .toList();
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
        return seed.fields();
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

    private Object vRankThresholdBusinessValue(String field, String value) {
        return switch (field) {
            case "selfBuy", "teamGv" -> BigDecimal.valueOf(moneyLabelToInt(value, 0));
            case "directRefs", "legCount" -> parseDecimal(value).setScale(0, RoundingMode.DOWN).intValue();
            case "legRank" -> value;
            default -> throw new IllegalArgumentException("Unsupported F1 V-Rank threshold field");
        };
    }

    private int leadershipUnlockRank() {
        return leadershipRanks().stream()
                .filter(row -> intValue(row.get("votes"), 0) > 0)
                .mapToInt(row -> intValue(row.get("v"), 0))
                .min()
                .orElse(0);
    }

    private String quotaUnlock(String code) {
        return quotaRows().stream()
                .filter(row -> textValue(row, "name", "").toUpperCase(Locale.ROOT).contains(code))
                .findFirst()
                .map(row -> "月配额 " + row.get("cap"))
                .orElse("");
    }

    private int intConfig(String key, int fallback) {
        return configDecimal(key, BigDecimal.valueOf(fallback)).setScale(0, RoundingMode.DOWN).intValue();
    }

    private String uiConfigKey(String key) {
        return "team.ui." + key;
    }

    private static Set<String> uiConfigKeys() {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(List.of(
                "F.vrank.leadership.unlockRank",
                "F.vrank.leadership.topN"));
        for (int i = 1; i <= 12; i++) {
            keys.add("F.fulfillment.V" + i + ".queue.status");
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
                "F.sunset.exclusions",
                "F.binary.threshold",
                "F.binary.matchRate",
                "F.binary.spillover",
                "F.binary.gvResetCron",
                "F.binary.settlePeriod",
                "F.binary.residualPolicy",
                "F3.dailyCap.currentLabel",
                "F3.dailyCap.windowLabel",
                "F3.dailyCap.nextTrigger",
                "F3.dailyCap.nextLabel",
                "F.pool.ratio",
                "F.pool.monthlyCap",
                "F.vrank.titles",
                "F.prize.name",
                "F.pool.settleCron",
                "F.pool.unlockVRank",
                // 批1b · 中风险:开关 / 数字范围 / JSON schema(配置面存储,业务逻辑消费留后续)。
                "F.vrank.permanent",
                "F.leaderboard.paused",
                "F.leaderboard.minUsd",
                "F.unilevel.depth",
                "F.commission.anomalyThreshold",
                // 批1c · 高风险:资金/结算语义型配置(配置面存储 + schema 校验;业务逻辑消费 + B1 红线归属留后续批次)。
                "F.binary.paused",
                "F.pool.top1MaxPct",
                "F.pool.top5MaxPct",
                "F.pool.periodPrize",
                "F.unilevel.L1.paused",
                "F.unilevel.L2.paused",
                "F.unilevel.L3.paused",
                "F.unilevel.L4.paused",
                "F.unilevel.L5.paused",
                "F.unilevel.L6.paused",
                "F.unilevel.L7.paused",
                "F.partner.tiers"));
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
                .orElse(BigDecimal.ZERO);
    }

    private String configText(String key, String fallback) {
        return configFacade.activeValue(uiConfigKey(key))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .orElse("");
    }

    private boolean boolConfig(String key, boolean fallback) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .map(value -> {
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    return "true".equals(normalized) || "1".equals(normalized) || "on".equals(normalized);
                })
                .orElse(false);
    }

    private BigDecimal parseDecimal(String raw) {
        return parseDecimal(raw, null);
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            if (fallback != null) {
                return BigDecimal.ZERO;
            }
            throw new IllegalArgumentException("Numeric value is required");
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(",", ""));
        } catch (NumberFormatException ex) {
            if (fallback != null) {
                return BigDecimal.ZERO;
            }
            throw new IllegalArgumentException("Numeric value is invalid", ex);
        }
    }

    private BigDecimal percentRatio(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            return BigDecimal.ZERO;
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
            return 0;
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

    private static VRankSeed vRank(String v, String... fields) {
        return new VRankSeed(v, Set.of(fields));
    }

    private record VRankSeed(String v, Set<String> fields) {
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

}
