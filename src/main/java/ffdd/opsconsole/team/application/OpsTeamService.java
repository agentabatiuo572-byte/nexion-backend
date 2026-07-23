package ffdd.opsconsole.team.application;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
import ffdd.opsconsole.team.domain.VRankConfigRow;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.team.dto.VRankOverrideRequest;
import ffdd.opsconsole.team.dto.VRankPromotionLogQuery;
import ffdd.opsconsole.team.dto.VRankRewardPayoutActionRequest;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
@Slf4j
public class OpsTeamService implements AuditReplayable {
    private static final String COMMISSION_COOLING_DAYS_KEY = "commission/cooling-days";
    private static final int DEFAULT_COMMISSION_COOLING_DAYS = 30;
    private static final Set<String> ACTIVE_KEYS = Set.of(
            "directRoyaltyPct",
            "networkRoyaltyPct",
            "binaryPairRatePct",
            "maxCombinedOutflowPct",
            "minPayoutUsdt",
            "rankWindowDays",
            "hardwareQuotaPerRank");
    /**
     * updateConfig/updateUiConfig key→权限码(service 层二次精确校验,防跨域越权)。
     * 涵盖 ACTIVE_KEYS(数值政策) + UI keys(F.* 配置面、动态 key 由 {@link #resolvePermissionCode} 前缀兜底)。
     * F.commission.{id}.status 静态映射到 dispose 类;reject 类(rejected 终态)由 updateCommissionEventStatus
     * 按 toCanonical 二次分流到 network_f5_commission_reject,EF.sql:64-65 注册。
     */
    private static final Map<String, String> CONFIG_KEY_PERMISSION = Map.ofEntries(
            Map.entry("directRoyaltyPct", "network_f2_royalty_rate"),
            Map.entry("networkRoyaltyPct", "network_f2_royalty_rate"),
            Map.entry("binaryPairRatePct", "network_f2_royalty_rate"),
            Map.entry("maxCombinedOutflowPct", "network_f2_royalty_rate"),
            Map.entry("minPayoutUsdt", "network_f2_royalty_rate"),
            Map.entry("rankWindowDays", "network_f2_write"),
            Map.entry("hardwareQuotaPerRank", "network_f2_write"),
            Map.entry("promo", "network_f2_policy_amplify"),
            Map.entry("peer", "network_f2_policy_amplify"),
            Map.entry("clampMin", "network_f2_policy_amplify"),
            Map.entry("clampMax", "network_f2_policy_amplify"),
            Map.entry("cool", "network_f2_write"),
            Map.entry("min", "network_f2_write"),
            Map.entry("depth", "network_f2_write"),
            Map.entry("nexcap", "network_f2_write"),
            Map.entry("backfill", "network_f2_write"),
            Map.entry("binary-rate", "network_f3_match_rate"),
            Map.entry("pool-ratio", "network_f4_pool_fund"),
            Map.entry("binary-threshold", "network_f3_write"),
            // F1 V-Rank 不降级开关:HIGH 权限点 network_f1_permanent_protection(EF.sql:44 注册,02-role-permission-seed:7 全量授权 SUPER_ADMIN)。
            // controller @PreAuthorize 兜底为 F2/F3/F4 任一,service 层按 key 精确校验 F1 专属权限,防越权。
            Map.entry("F.vrank.permanent", "network_f1_permanent_protection"),
            // F 域 UI keys 精确权限映射(A1 批1a 修复4):静态 key 直查;F.unilevel.L{n}/F.commission.{id} 动态 key 走 resolvePermissionCode 前缀。
            // F2 版税费率类:promo 周倍率/peer 平级比例/unilevel 版税费率 → royalty_rate(HIGH)。
            Map.entry("F.promo.weekMultiplier", "network_f2_royalty_rate"),
            Map.entry("F.peer.rate", "network_f2_royalty_rate"),
            // F3 双轨类:matchRate 阈值类放大 → match_rate(HIGH);threshold/spillover/settlePeriod 等走 write。
            Map.entry("F.binary.matchRate", "network_f3_match_rate"),
            Map.entry("F.binary.threshold", "network_f3_match_rate"),
            // F4 领导池资金类 → pool_fund(HIGH)。
            Map.entry("F.pool.ratio", "network_f4_pool_fund"),
            Map.entry("F.pool.top1MaxPct", "network_f4_pool_fund"),
            Map.entry("F.pool.top5MaxPct", "network_f4_pool_fund"),
            Map.entry("F.pool.periodPrize", "network_f4_pool_fund"),
            // F4 · A1 批2b 修复1:F4 主流程按钮 key 对齐白名单 + 权限码。
            // 票权/配额常规写 → f4_write;大使审批 → f4_ambassador_approve(HIGH);榜单控制 → f4_leaderboard_control(HIGH)。
            Map.entry("F.quota.proUnlock", "network_f4_write"),
            Map.entry("F.quota.rackUnlock", "network_f4_write"),
            Map.entry("F.quota.monthlyStock", "network_f4_write"),
            Map.entry("F.leaderboard.poolUsd", "network_f4_leaderboard_control"),
            Map.entry("F.leaderboard.period.status", "network_f4_leaderboard_control"),
            // F5 佣金事件 dispose 类默认权限;rejected 终态由 updateCommissionEventStatus 二次分流到 commission_reject。
            Map.entry("F.commission.status", "network_f5_commission_dispose"));

    /**
     * 动态 key 前缀→权限码兜底(A1 批1a 修复4):CONFIG_KEY_PERMISSION 静态 Map 无法覆盖 F.unilevel.L{n}/F.commission.{id}
     * 等动态片段,故按前缀校验。未匹配返回 null(controller @PreAuthorize 已兜底)。
     */
    private String resolvePermissionCode(String key) {
        if (key == null) {
            return null;
        }
        String exact = CONFIG_KEY_PERMISSION.get(key);
        if (exact != null) {
            return exact;
        }
        // F.unilevel.L{n} / F.unilevel.nex.L{n}(版税费率,放大佣金流出)→ royalty_rate
        if (key.startsWith("F.unilevel.L") || key.startsWith("F.unilevel.nex.L")) {
            return "network_f2_royalty_rate";
        }
        // F.binary.*(默认 match_rate;threshold/matchRate 已在静态表精确映射,其余 binary 字段保守要求 match_rate 防越权)
        if (key.startsWith("F.binary.")) {
            return "network_f3_match_rate";
        }
        // F.pool.* → pool_fund
        if (key.startsWith("F.pool.")) {
            return "network_f4_pool_fund";
        }
        // F.commission.{id}.status → dispose 默认;rejected 由 updateCommissionEventStatus 二次分流 commission_reject
        if (key.startsWith("F.commission.") && key.endsWith(".status")) {
            return "network_f5_commission_dispose";
        }
        // F4 · A1 批2b 修复2:F.pool.votes.V{n} 票权动态分发 → f4_write(权重调整非直接放大流出,走常规写)。
        if (key.startsWith("F.pool.votes.")) {
            return "network_f4_write";
        }
        // F4 · A1 批2b 修复3:F.ambassador.{label}.status 动态分发 → f4_ambassador_approve(HIGH 大使审批专用)。
        if (key.startsWith("F.ambassador.") && key.endsWith(".status")) {
            return "network_f4_ambassador_approve";
        }
        // F4 · A1 批2b 修复4:F.leaderboard.{poolUsd|period.status|paused} 动态分发 → f4_leaderboard_control(HIGH 榜单控制)。
        if (key.startsWith("F.leaderboard.")) {
            return "network_f4_leaderboard_control";
        }
        // F4 · A1 批2b 修复1:F.quota.{proUnlock|rackUnlock|monthlyStock} 配置层兜底 → f4_write(配额门槛为展示态,非资金直接放大)。
        if (key.startsWith("F.quota.")) {
            return "network_f4_write";
        }
        return null;
    }
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
    private final AdminPermissionCache permissionCache;
    private final AuditObjectLockMapper lockMapper;
    // Sprint5:F1 V-Rank 晋升引擎 + 奖励派发器(evaluate 端点调引擎,override promote 方向直调 dispatcher 越级派奖)
    private final VRankPromotionEngine vRankPromotionEngine;
    private final VRankRewardDispatcher vRankRewardDispatcher;
    private final EventOutboxService eventOutboxService;

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
        // B1 备付金覆盖率快照:供前端 F4 OperationConfirmModal 对资金类调参(领导池比例/榜单奖池)显示覆盖率 + covBlocked 拦截,范式同 ranks()。
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> coverageOverview = new LinkedHashMap<>();
        coverageOverview.put("coverageRatio", coverage.coverageRatio());
        coverageOverview.put("redlinePct", coverage.redlinePct());
        response.put("coverage", coverageOverview);
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
        // B1 备付金覆盖率快照:供前端 F5 OperationConfirmModal 对资金类处置(冻结/解锁/红冲/结算/支付)显示覆盖率 + covBlocked 拦截,范式同 ranks()。
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> coverageOverview = new LinkedHashMap<>();
        coverageOverview.put("coverageRatio", coverage.coverageRatio());
        coverageOverview.put("redlinePct", coverage.redlinePct());
        response.put("coverage", coverageOverview);
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
        // B1 备付金覆盖率快照:供前端 F1 OperationConfirmModal 对资金类奖励显示覆盖率 + covBlocked 拦截。
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> coverageOverview = new LinkedHashMap<>();
        coverageOverview.put("coverageRatio", coverage.coverageRatio());
        coverageOverview.put("redlinePct", coverage.redlinePct());
        response.put("coverage", coverageOverview);
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
        // B1 备付金覆盖率快照:供前端 F2 OperationConfirmModal 对版税费率/peer/promo 资金类调参显示覆盖率 + covBlocked 拦截,范式同 ranks()。
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> coverageOverview = new LinkedHashMap<>();
        coverageOverview.put("coverageRatio", coverage.coverageRatio());
        coverageOverview.put("redlinePct", coverage.redlinePct());
        response.put("coverage", coverageOverview);
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
        // B1 备付金覆盖率快照:供前端 F3 OperationConfirmModal 对平衡匹配率/门槛资金类调参显示覆盖率 + covBlocked 拦截,范式同 ranks()。
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> coverageOverview = new LinkedHashMap<>();
        coverageOverview.put("coverageRatio", coverage.coverageRatio());
        coverageOverview.put("redlinePct", coverage.redlinePct());
        response.put("coverage", coverageOverview);
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
        // 资金类奖励(USDT/NEX,金额>0)放大平台资金流出,B1 备付金覆盖率红线预检;低于红线直接拒。
        if (isFundRewardItem(item) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
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
        // 资金类奖励(USDT/NEX,金额>0)放大平台资金流出,B1 备付金覆盖率红线预检;低于红线直接拒。
        if (isFundRewardItem(item) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
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

    @Transactional
    public ApiResult<Map<String, Object>> updateConfig(String idempotencyKey, TeamCommissionConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = requireText(request.key(), "TEAM_CONFIG_KEY_REQUIRED");
        // service 层二次精确校验(A1 批1a 修复4):ACTIVE_KEYS + UI keys 全覆盖,动态 key 前缀兜底,防跨域越权(如持 F2 改 F4)。
        String requiredCode = resolvePermissionCode(key);
        if (requiredCode != null && !A2ReplayContext.isReplaying()) {
            Long adminId = parseAdminIdFromContext();
            if (adminId == null) {
                return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
            }
            if (!permissionCache.getPermissionCodes(adminId).contains(requiredCode)) {
                return ApiResult.fail(403, "PERMISSION_DENIED");
            }
        }
        rejectSunsetKey(key);
        if (!ACTIVE_KEYS.contains(key)) {
            return updateUiConfig(idempotencyKey, request, key);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "team_config", key) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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

    private Long parseAdminIdFromContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(authentication.getPrincipal()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ApiResult<Map<String, Object>> updateUiConfig(
            String idempotencyKey, TeamCommissionConfigUpdateRequest request, String key) {
        // A1 批1a 修复4:UI keys 跨域越权收敛(原仅 ACTIVE_KEYS 二次校验,UI keys 跳过 → 持 F2 可改 F4)。
        String requiredCode = resolvePermissionCode(key);
        if (requiredCode != null && !A2ReplayContext.isReplaying()) {
            Long adminId = parseAdminIdFromContext();
            if (adminId == null) {
                return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
            }
            if (!permissionCache.getPermissionCodes(adminId).contains(requiredCode)) {
                return ApiResult.fail(403, "PERMISSION_DENIED");
            }
        }
        Optional<String> commissionEventId = commissionStatusEventId(key);
        if (commissionEventId.isPresent()) {
            return updateCommissionEventStatus(idempotencyKey, request, key, commissionEventId.get());
        }
        if (isUnilevelRuleKey(key)) {
            return updateUnilevelRule(idempotencyKey, request, key);
        }
        // A1 批2b 修复2:F.pool.votes.V{n} → 写业务表 nx_v_rank_config.leadership_votes。
        if (isVRankVoteKey(key)) {
            return updateVRankVotes(idempotencyKey, request, key);
        }
        // A1 批2b 修复3:F.ambassador.{label}.status → 写业务表 nx_team_ambassador_application.status。
        if (isAmbassadorStatusKey(key)) {
            return updateAmbassadorStatus(idempotencyKey, request, key);
        }
        // A1 批2b 修复4:F.leaderboard.period.status=disqualified → INSERT 业务表 nx_team_leaderboard_action 流水。
        if (isLeaderboardPeriodStatusKey(key)) {
            return updateLeaderboardPeriodStatus(idempotencyKey, request, key);
        }
        if (!UI_CONFIG_KEYS.contains(key)) {
            throw new IllegalArgumentException("Unsupported F team UI config key");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "ui_config", key) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String value = normalizeUiValue(request.value());
        validateUiConfig(key, value);
        String configKey = uiConfigKey(key);
        String oldValue = configFacade.activeValue(configKey).orElse("");
        // A1 批1a 修复2:全域 B1 资金护栏接线(原 UI keys 路径完全跳过 loosensPayoutControl + coverageBelowRedline)。
        // 资金放大类 UI key(费率/比例上调、门槛下调)在 B1 红线下阻断,范式同 updateConfig:434-436。
        if (loosensPayoutControlUiKey(key, oldValue, value) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        configFacade.upsertAdminValue(configKey, value, "TEXT", "team", "F domain UI-backed policy state");
        // P1 桥接:F.cooldown 配置面写入后同步到引擎读的 commission/cooling-days(消除 UI 配置与引擎割裂)
        bridgeCooldownToCommission(key, value);
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
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "unilevel_rule", "L" + layerNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String field = key.startsWith("F.unilevel.nex.") ? "nexPerUsd" : "usdtRate";
        Object businessValue = "usdtRate".equals(field)
                ? percentRatio(value, BigDecimal.ZERO)
                : parseDecimal(value, BigDecimal.ZERO);
        Map<String, Object> oldRow = unilevelRates().stream()
                .filter(row -> ("L" + layerNo).equals(row.get("level")))
                .findFirst()
                .orElse(Map.of());
        String oldValue = String.valueOf(oldRow.getOrDefault("usdtRate".equals(field) ? "usdtPct" : "nexReward", ""));
        // A1 批1a 修复2:unilevel 版税费率上调放大佣金流出,B1 红线下阻断(usdtRate / nexPerUsd 上调 = 放大)。
        if (loosensPayoutControlUiKey(key, oldValue, value) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
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

    /**
     * A1 批2b 修复2:F4 票权(领导池权重)写业务表 nx_v_rank_config.leadership_votes。
     * F.pool.votes.V{n} → 解析 V{n} → UPDATE leadership_votes WHERE rank_code=V{n}。
     * 票权权重放大头部分润,B1 资金护栏 loosensPayoutControl 不直连(权重非比例/阈值,业务复杂度留后续);
     * 当前保守策略:_votes 调高 = 放大头部虹吸,放大值时受 B1 红线阻断(见 coverageBelowRedline)。
     */
    private ApiResult<Map<String, Object>> updateVRankVotes(
            String idempotencyKey,
            TeamCommissionConfigUpdateRequest request,
            String key) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "v_rank_votes", key) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String value = normalizeUiValue(request.value());
        int votes = parseDecimal(value, BigDecimal.valueOf(-1)).setScale(0, RoundingMode.DOWN).intValueExact();
        if (votes < 0 || votes > 1_000_000) {
            throw new IllegalArgumentException("F_TEAM_VOTES_OUT_OF_RANGE");
        }
        String rankCode = key.substring("F.pool.votes.".length());
        Map<String, Object> before = vrankRows().stream()
                .filter(row -> rankCode.equals(row.get("v")))
                .findFirst()
                .orElse(Map.of());
        String oldValue = String.valueOf(before.getOrDefault("votes", "0"));
        // 票权权重调高 = 放大头部虹吸(领导池分配按 votes 权重),受 B1 红线阻断。
        if (votes > parseIntSafe(oldValue, 0) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        if (!commissionRepository.updateVRankLeadershipVotes(rankCode, votes)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "VRANK_VOTES_BUSINESS_TABLE_UPDATE_FAILED");
        }
        String resourceId = "nx_v_rank_config:" + rankCode + ".leadership_votes";
        audit("F_TEAM_VRANK_VOTES_CHANGED", resourceId, request.operator(), Map.of(
                "key", key,
                "rankCode", rankCode,
                "oldValue", oldValue,
                "newValue", votes,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = leadershipPool().getData();
        response.put("updated", Map.of("key", key, "source", "nx_v_rank_config", "oldValue", oldValue, "newValue", votes));
        return ApiResult.ok(response);
    }

    /**
     * A1 批2b 修复3:F4 大使审批写业务表 nx_team_ambassador_application.status。
     * F.ambassador.{label}.status → label 数字按 id 精确匹配;非数字(如 q3-2025)→ 最新 PENDING 一条兜底。
     * approved=开通预算(资金放大,reject/dispose 分权限:approved 走 ambassador_approve;rejected 亦走同权限)。
     */
    private ApiResult<Map<String, Object>> updateAmbassadorStatus(
            String idempotencyKey,
            TeamCommissionConfigUpdateRequest request,
            String key) {
        String label = key.substring("F.ambassador.".length(), key.length() - ".status".length());
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "ambassador_application", label) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String value = normalizeUiValue(request.value());
        String canonical = canonicalAmbassadorStatus(value);
        // approved(开通 4 类预算额度)是资金放大动作,B1 红线下阻断;rejected(不开通预算)非放大。
        if ("APPROVED".equals(canonical) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        if (!commissionRepository.updateAmbassadorStatus(label, canonical, request.operator(), request.reason().trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "AMBASSADOR_APPLICATION_UPDATE_FAILED");
        }
        String resourceId = "nx_team_ambassador_application:" + label + ".status";
        audit("F_TEAM_AMBASSADOR_REVIEWED", resourceId, request.operator(), Map.of(
                "key", key,
                "applicationLabel", label,
                "newValue", canonical,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = leadershipPool().getData();
        response.put("updated", Map.of("key", key, "source", "nx_team_ambassador_application", "newValue", canonical));
        return ApiResult.ok(response);
    }

    /**
     * A1 批2b 修复4:F4 榜单期级处置(disqualified)INSERT 业务表 nx_team_leaderboard_action 流水。
     * F.leaderboard.period.status=disqualified → INSERT action_type='DISQUALIFIED', period='week', member_user_id=0(期级全局处置)。
     * paused/minUsd/poolUsd 三个 key 不进此分支(走配置层默认路径)。
     */
    private ApiResult<Map<String, Object>> updateLeaderboardPeriodStatus(
            String idempotencyKey,
            TeamCommissionConfigUpdateRequest request,
            String key) {
        String value = normalizeUiValue(request.value());
        String actionType = canonicalLeaderboardAction(value);
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "leaderboard_action", "week") > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (!commissionRepository.insertLeaderboardAction("week", actionType, request.reason().trim(), request.operator())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEADERBOARD_ACTION_INSERT_FAILED");
        }
        String resourceId = "nx_team_leaderboard_action:week." + actionType;
        audit("F_TEAM_LEADERBOARD_CHANGED", resourceId, request.operator(), Map.of(
                "key", key,
                "period", "week",
                "actionType", actionType,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = leadershipPool().getData();
        response.put("updated", Map.of("key", key, "source", "nx_team_leaderboard_action", "actionType", actionType));
        return ApiResult.ok(response);
    }

    /** F4 大使状态归一:approved/rejected/pending 三态化(其余原样大写)。 */
    private String canonicalAmbassadorStatus(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "approved", "通过", "已批" -> "APPROVED";
            case "rejected", "驳回", "已驳" -> "REJECTED";
            case "pending", "待确认" -> "PENDING";
            default -> normalized.toUpperCase(Locale.ROOT);
        };
    }

    /** F4 榜单 action 归一:disqualified/paused/fraud 三态化(其余原样大写)。 */
    private String canonicalLeaderboardAction(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "disqualified", "取消资格" -> "DISQUALIFIED";
            case "paused", "暂停" -> "PAUSED";
            case "fraud", "fraudhit", "刷榜" -> "FRAUD";
            default -> normalized.toUpperCase(Locale.ROOT);
        };
    }

    private int parseIntSafe(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private ApiResult<Map<String, Object>> updateCommissionEventStatus(
            String idempotencyKey,
            TeamCommissionConfigUpdateRequest request,
            String key,
            String eventId) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "commission_event", eventId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String value = normalizeUiValue(request.value());
        Map<String, Object> oldEvent = commissionEvents().stream()
                .filter(row -> eventId.equals(row.get("id")))
                .findFirst()
                .orElse(null);
        if (oldEvent == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMMISSION_EVENT_NOT_FOUND");
        }
        // A1 批1a 修复1:F5 佣金事件状态机校验(原接受任意 status,REJECTED→UNLOCKED 静默复活=资金漏洞)。
        String fromCanonical = canonicalCommissionState(String.valueOf(oldEvent.get("state")));
        String toCanonical = canonicalCommissionState(value);
        if (!isLegalCommissionStateTransition(fromCanonical, toCanonical)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        // A1 批1a 修复3:F5 权限强制 — dispose(frozen/unlocked/settled/paid)与 reject(rejected 终态)分权限。
        // EF.sql:64-65 注册 network_f5_commission_dispose / network_f5_commission_reject,controller:106 @PreAuthorize 兜底任一。
        if (!A2ReplayContext.isReplaying()) {
            Long adminId = parseAdminIdFromContext();
            if (adminId == null) {
                return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
            }
            String f5Required = "REJECTED".equals(toCanonical)
                    ? "network_f5_commission_reject"
                    : "network_f5_commission_dispose";
            if (!permissionCache.getPermissionCodes(adminId).contains(f5Required)) {
                return ApiResult.fail(403, "PERMISSION_DENIED");
            }
        }
        if (!commissionRepository.updateCommissionStatus(eventId, value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMMISSION_EVENT_UPDATE_FAILED");
        }
        publishCommissionUnlockedIfEligible(fromCanonical, toCanonical, eventId, oldEvent);
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

    /**
     * H3 canonical quest trigger. Only the real COOLING -> UNLOCKED transition emits the event;
     * replayed/same-state requests and other legal dispositions stay silent. EventOutboxService
     * persists internal events as server-authoritative envelopes. Because this runs inside
     * {@link #updateConfig(String, TeamCommissionConfigUpdateRequest)}, an outbox failure rolls
     * back the commission status update in the same transaction.
     */
    private void publishCommissionUnlockedIfEligible(
            String fromCanonical,
            String toCanonical,
            String eventId,
            Map<String, Object> oldEvent) {
        if (!"COOLING".equals(fromCanonical) || !"UNLOCKED".equals(toCanonical)) {
            return;
        }
        Long userId = userIdFromText(String.valueOf(oldEvent.get("user")));
        if (userId <= 0) {
            throw new IllegalStateException("COMMISSION_EVENT_USER_ID_INVALID");
        }
        eventOutboxService.publish(
                "COMMISSION",
                eventId,
                "COMMISSION_UNLOCKED",
                Map.of("user_id", userId, "commission_event_id", eventId));
    }

    /**
     * F5 佣金事件状态归一化(PRD F5②:cooling/unlocked/withdrawn/reversed + 任务 A1 转换图 frozen/settled/paid)。
     * nx_commission_event 历史数据混用中英文,统一归一为大写英文供状态机判断。
     */
    private String canonicalCommissionState(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "计提", "cooling" -> "COOLING";
            case "可提", "可提现", "unlocked" -> "UNLOCKED";
            case "frozen", "冻结" -> "FROZEN";
            case "异常回退", "rejected", "reversed", "驳回", "红冲" -> "REJECTED";
            case "settled", "已结算" -> "SETTLED";
            case "withdrawn", "paid", "已提现", "已支付" -> "PAID";
            default -> normalized.toUpperCase(Locale.ROOT);
        };
    }

    /**
     * F5 状态转换图(任务 A1 保守版):
     *   COOLING → {UNLOCKED, FROZEN, REJECTED}  冷却到期解锁 / 运营冻结 / 撤销
     *   UNLOCKED → {FROZEN, REJECTED, SETTLED, PAID}  运营冻结 / 红冲 / 结算 / 支付
     *   FROZEN → {UNLOCKED, REJECTED}  解冻 / 撤销(冻结不可直接 SETTLED/PAID,须先解冻)
     *   SETTLED → PAID  结算→支付
     *   REJECTED 终态(红冲不可复活,核心资金安全约束)
     *   PAID 终态(已支付不可逆)
     */
    private static final Map<String, Set<String>> COMMISSION_STATE_TRANSITIONS = Map.of(
            "COOLING", Set.of("UNLOCKED", "FROZEN", "REJECTED"),
            "UNLOCKED", Set.of("FROZEN", "REJECTED", "SETTLED", "PAID"),
            "FROZEN", Set.of("UNLOCKED", "REJECTED"),
            "SETTLED", Set.of("PAID"));

    private boolean isLegalCommissionStateTransition(String fromCanonical, String toCanonical) {
        if (fromCanonical.equals(toCanonical)) {
            return false;
        }
        Set<String> allowed = COMMISSION_STATE_TRANSITIONS.get(fromCanonical);
        return allowed != null && allowed.contains(toCanonical);
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
        String canonical = canonicalCommissionState(state);
        // A1 批1a 修复1b:台账对冲修正。
        // COOLING/UNLOCKED 是前置态(COOLING 仍在冷却、UNLOCKED 已可提为 IN/PENDING 应付),FROZEN 是运营冻结(不动余额),
        // 这三类不重复 post 流出条目;仅 REJECTED(红冲对冲)/SETTLED(结算流出)/PAID(支付流出)写 OUT/SUCCESS。
        // 防 frozen 被误标 OUT(任务原文"frozen 不应标 OUT,冻结不动余额")与重复台账(原每次状态变化都 post 一条)。
        if ("COOLING".equals(canonical) || "UNLOCKED".equals(canonical) || "FROZEN".equals(canonical)) {
            return;
        }
        ledgerPostingFacade.postLedgerEntry(
                "F5-COMMISSION-" + eventId.get() + "-" + ledgerStateCode(state),
                userIdFromText(String.valueOf(event.get("user"))),
                "TEAM_COMMISSION",
                String.valueOf(event.getOrDefault("currency", "USDT")),
                commissionLedgerDirection(state),
                amount,
                commissionLedgerStatus(state),
                "F5 commission status disposition | eventId=" + eventId.get() + " | state=" + state + " | canonical=" + canonical);
    }

    /**
     * A1 批1a 修复2:全域 B1 资金护栏接线 — UI keys 资金放大判断。
     * 费率/比例上调 = 放大;门槛下调 = 放大。复盖:
     *   F2 unilevel.L{n}(usdtRate 上调)/promo.weekMultiplier 上调/peer.rate 上调
     *   F3 binary.matchRate 上调 / binary.threshold 下调
     *   F4 pool.ratio/top1MaxPct/top5MaxPct/periodPrize 上调
     * 解析失败返回 false(由 validateUiConfig 上层报错,避免误判放大)。
     */
    private boolean loosensPayoutControlUiKey(String key, String oldValue, String newValue) {
        if (key == null) {
            return false;
        }
        try {
            if (isUnilevelRuleKey(key)) {
                if (key.startsWith("F.unilevel.nex.L")) {
                    // nexPerUsd 上调 = 每美元兑更多 NEX,放大 NEX 派发
                    return parseDecimal(newValue, BigDecimal.ZERO).compareTo(parseDecimal(oldValue, BigDecimal.ZERO)) > 0;
                }
                // usdtRate 百分比上调 = 版税费率放大
                return percentRatio(newValue, BigDecimal.ZERO).compareTo(percentRatio(oldValue, BigDecimal.ZERO)) > 0;
            }
            return switch (key) {
                case "F.promo.weekMultiplier", "F.peer.rate", "F.pool.periodPrize" ->
                        parseDecimal(newValue, BigDecimal.ZERO).compareTo(parseDecimal(oldValue, BigDecimal.ZERO)) > 0;
                case "F.binary.matchRate", "F.pool.ratio", "F.pool.top1MaxPct", "F.pool.top5MaxPct" ->
                        percentRatio(newValue, BigDecimal.ZERO).compareTo(percentRatio(oldValue, BigDecimal.ZERO)) > 0;
                // 门槛下调 = 放大(更低门槛触发更多结算)
                case "F.binary.threshold" ->
                        parseDecimal(newValue, BigDecimal.ZERO).compareTo(parseDecimal(oldValue, BigDecimal.ZERO)) < 0;
                default -> false;
            };
        } catch (IllegalArgumentException ex) {
            return false;
        }
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

    /**
     * A1 批2b 修复2:F.pool.votes.V{n} 动态 key 判定。
     * V{n} 中 n 必须为 1-12 数字(V0 无票权、不入权重表;前端 voteWeights 来自 leadershipRanks 已过滤 votes>0)。
     */
    private boolean isVRankVoteKey(String key) {
        if (!key.startsWith("F.pool.votes.")) {
            return false;
        }
        String raw = key.substring("F.pool.votes.".length());
        if (!StringUtils.hasText(raw) || !raw.matches("V\\d{1,2}")) {
            return false;
        }
        try {
            int n = Integer.parseInt(raw.substring(1));
            return n >= 1 && n <= 12;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * A1 批2b 修复3:F.ambassador.{label}.status 动态 key 判定。
     * label 可为数字 application id(逐条审批)或自由文本(如 q3-2025 周期级 → 最新 PENDING 兜底)。
     * status 取请求 value(approved/rejected),rejected 走 reject 权限分流(二次校验在 updateAmbassadorStatus 内)。
     */
    private boolean isAmbassadorStatusKey(String key) {
        if (!key.startsWith("F.ambassador.") || !key.endsWith(".status")) {
            return false;
        }
        String label = key.substring("F.ambassador.".length(), key.length() - ".status".length());
        return StringUtils.hasText(label) && !label.contains(".");
    }

    /**
     * A1 批2b 修复4:F.leaderboard.period.status 动态 key 判定。
     * 仅 period.status(disqualified/paused 等期级处置)走 INSERT 流水;poolUsd/paused/minUsd 走配置层默认路径。
     */
    private boolean isLeaderboardPeriodStatusKey(String key) {
        return "F.leaderboard.period.status".equals(key);
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
        // A1 批1a 修复1b:UNLOCKED=IN(用户可提应付入账);REJECTED/SETTLED/PAID=OUT(对冲/结算/支付流出)。
        // FROZEN/COOLING 由 postCommissionLedgerIfStatusChanged 提前 return,不进入此分支。
        String canonical = canonicalCommissionState(state);
        return "UNLOCKED".equals(canonical) ? "IN" : "OUT";
    }

    private String commissionLedgerStatus(String state) {
        // UNLOCKED=PENDING(应付待提);其余=SUCCESS(已对冲/结算/支付)。
        String canonical = canonicalCommissionState(state);
        return "UNLOCKED".equals(canonical) ? "PENDING" : "SUCCESS";
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
        cap.put("h1BinaryDailyCap", rhythm.binaryDailyCap());
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
        pool.put("leaderboardDisqualified", "disqualified".equalsIgnoreCase(leaderboardStatus));
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
            // 批2a · 修复2:F.cooldown 佣金冷却期范围校验 · 单位"天",范围 0-90。
            // 越界(如 -1 / 100)拒绝,防止误填导致佣金永不解锁或冷却过长影响可提余额释放。
            case "F.cooldown" -> {
                var days = parseDecimal(value, BigDecimal.valueOf(-1));
                if (days.signum() < 0 || days.compareTo(BigDecimal.valueOf(90)) > 0) {
                    throw new IllegalArgumentException("F_TEAM_COOLDOWN_OUT_OF_RANGE");
                }
            }
            // 批2a · 修复2:F.promo.weekMultiplier promo 周倍率范围校验 · 范围 1.0-3.0。
            // 越界(如 0.5 / 3.5)拒绝,防止放大佣金流出突破 B1 覆盖率(放大杠杆参数,amplifies=true)。
            case "F.promo.weekMultiplier" -> {
                var mult = parseDecimal(value, BigDecimal.valueOf(-1));
                if (mult.compareTo(BigDecimal.ONE) < 0 || mult.compareTo(BigDecimal.valueOf(3)) > 0) {
                    throw new IllegalArgumentException("F_TEAM_PROMO_MULT_OUT_OF_RANGE");
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

    /** 判定为资金类奖励:USDT/NEX 且金额>0。资金类放大平台资金流出,写入受 B1 备付金覆盖率约束。 */
    private boolean isFundRewardItem(Map<String, Object> item) {
        String type = String.valueOf(item.getOrDefault("type", ""));
        if (!"usdt".equals(type) && !"nex".equals(type)) {
            return false;
        }
        return decimalValue(item.get("amount"), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0;
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
                "F.partner.tiers",
                // A1 批2b 修复1:F4 主流程按钮 key 白名单补齐(票权/配额/榜单奖池)。
                // 票权 F.pool.votes.V{n} 由 isVRankVoteKey 动态分发到业务表,不在此静态白名单内,但 leadership_pool summary 已展示。
                // 配额 3 key(proUnlock/rackUnlock/monthlyStock)走配置层兜底(nx_team_hardware_quota_tier tier 级细化留后续)。
                "F.quota.proUnlock",
                "F.quota.rackUnlock",
                "F.quota.monthlyStock",
                // 榜单 poolUsd 走配置层(nx_team_leaderboard_action 无奖池字段,业务表落库留后续 D4 联动);
                // period.status 由 isLeaderboardPeriodStatusKey 动态分发到业务表 INSERT,不在此静态白名单。
                "F.leaderboard.poolUsd"));
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

    private int resolveCommissionCoolingDays() {
        return configFacade.activeValue(COMMISSION_COOLING_DAYS_KEY)
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .filter(days -> days >= 0 && days <= 90)
                .orElse(DEFAULT_COMMISSION_COOLING_DAYS);
    }

    /**
     * P1 桥接:F.cooldown(UI 配置面 team.ui.F.cooldown)→ commission/cooling-days(引擎 resolveCoolingDays 读的 key)。
     *
     * <p>背景:UI 配置面 admin PUT 写 team.ui.F.cooldown(如 "30"/"30d"),引擎 {@link UnilevelCommissionService#resolveCoolingDays}
     * 读 commission/cooling-days(F2/F3/F5 coolingDays)。两者割裂导致配置面改了引擎不生效。
     * 此处在 updateUiConfig 写完 UI key 后,同步 upsert commission/cooling-days 同值,桥接两个配置入口。
     *
     * <p>容错:UI 值可能带后缀(如 "30d"/"30 天"),提取前导数字;范围校验已由 validateUiConfig 兜底(0-90),
     * 此处二次 clamp 防御;解析失败仅告警不阻断(不破坏 UI 写入主流程)。
     *
     * @param key UI config key(仅 F.cooldown 触发桥接)
     * @param uiValue UI 原始值
     */
    private void bridgeCooldownToCommission(String key, String uiValue) {
        if (!"F.cooldown".equals(key)) {
            return;
        }
        try {
            // 提取前导数字(兼容 "30"/"30d"/"30 天" 等 UI 值)
            String digits = uiValue == null ? "" : uiValue.trim().replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) {
                log.warn("F.cooldown bridge skipped (no digits in uiValue={})", uiValue);
                return;
            }
            int days = Integer.parseInt(digits);
            if (days < 0 || days > 90) {
                log.warn("F.cooldown bridge skipped (out of range 0-90, uiValue={} → {})", uiValue, days);
                return;
            }
            configFacade.upsertAdminValue(
                    COMMISSION_COOLING_DAYS_KEY,
                    String.valueOf(days),
                    "NUMBER",
                    "team",
                    "P1 bridge from team.ui.F.cooldown (F domain commission cooling days)");
            log.info("F.cooldown bridge: uiValue={} → commission/cooling-days={}", uiValue, days);
        } catch (RuntimeException ex) {
            // 桥接失败不影响 UI 写入主流程(已 upsert team.ui.F.cooldown 成功)
            log.warn("F.cooldown bridge FAILED (uiValue={}): {}", uiValue, ex.getMessage());
        }
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

    // ============================================================
    // Sprint 5:F1 V-Rank 晋升引擎 HTTP 端点服务方法
    // ============================================================

    /**
     * 端点 1:手动触发用户 V-Rank 评估。
     *
     * <p>调 {@link VRankPromotionEngine#evaluate}(MANUAL ctx),返回 {before, after, promoted, rewards[]}:
     * <ul>
     *   <li>{@code before} — 评估前的当前阶(commissionRepository.currentMemberVRank)</li>
     *   <li>{@code after} — 引擎返回的新阶(engine.evaluate)</li>
     *   <li>{@code promoted} — before != after</li>
     *   <li>{@code rewards} — after 阶配置的奖励规则(nx_v_rank_reward_rule;实际派发流水查 payout 表)</li>
     * </ul>
     * 引擎内部已带 @Transactional + audit_no 幂等链,本方法无需 requireCommand。
     */
    public ApiResult<Map<String, Object>> evaluateVRank(Long userId) {
        if (userId == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        String before = commissionRepository.currentMemberVRank(userId);
        VRankPromotionContext ctx = new VRankPromotionContext(
                userId,
                VRankPromotionContext.TriggerType.MANUAL_OPERATION,
                null,
                resolveOperator("MANUAL"));
        String after = vRankPromotionEngine.evaluate(ctx);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("before", before);
        response.put("after", after);
        response.put("promoted", !before.equalsIgnoreCase(after));
        response.put("rewards", activeRewards(after));
        response.put("source", "VRankPromotionEngine.evaluate");
        return ApiResult.ok(response);
    }

    /**
     * 端点 2:F1-MD1 手动晋升/回滚(越级 / 降阶处置)。
     *
     * <p>流程:① requireCommand(Idempotency-Key + reason) ② targetV ∈ V0-V12 + direction 与 (targetV vs currentV) 一致
     * ③ A2 对象锁 lockMapper.countActiveByTarget("F","vrank_override",userId)>0 → 409 OBJECT_LOCKED_BY_A2
     * ④ @Transactional:UPDATE nx_team_member.v_rank + INSERT nx_user_level_log(is_manual=1, reason="[MANUAL]"+reason)
     * ⑤ promote 方向调 rewardDispatcher.dispatch(MANUAL ctx) 越级派奖;rollback 不派奖不剥夺
     * ⑥ audit F_TEAM_VRANK_OVERRIDDEN ⑦ 响应含联动预览(unilevelDepth/peerBonus/votes 从 nx_v_rank_config before/after 读)
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> overrideVRank(Long userId, String idempotencyKey, VRankOverrideRequest request) {
        if (userId == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey,
                request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String targetV = requireText(request.targetV(), "TEAM_VRANK_REQUIRED").toUpperCase(Locale.ROOT);
        if (!VRANK_LEVELS.contains(targetV)) {
            throw new IllegalArgumentException("Unsupported F1 V-Rank level");
        }
        String direction = requireText(request.direction(), "TEAM_VRANK_DIRECTION_REQUIRED")
                .toLowerCase(Locale.ROOT);
        if (!"promote".equals(direction) && !"rollback".equals(direction)) {
            throw new IllegalArgumentException("F1 V-Rank direction must be promote or rollback");
        }
        String currentV = commissionRepository.currentMemberVRank(userId);
        int currentIdx = vRankIndex(currentV);
        int targetIdx = vRankIndex(targetV);
        // 方向一致性校验:promote 必须 targetV > currentV;rollback 必须 targetV < currentV
        if ("promote".equals(direction) && targetIdx <= currentIdx) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "VRANK_OVERRIDE_DIRECTION_MISMATCH: promote requires targetV > currentV");
        }
        if ("rollback".equals(direction) && targetIdx >= currentIdx) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "VRANK_OVERRIDE_DIRECTION_MISMATCH: rollback requires targetV < currentV");
        }
        // A2 对象锁:同 userId 存在未完成 A2 提案 → 409 OBJECT_LOCKED_BY_A2(对齐 updateConfig 范式)
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("F", "vrank_override", String.valueOf(userId)) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String operator = resolveOperator("MANUAL", request.operator());
        String manualReason = "[MANUAL] " + request.reason().trim();
        boolean updated = commissionRepository.updateMemberVRank(userId, targetV);
        if (!updated) {
            log.warn("V-Rank override: member self-loop row missing for user {}, skipping UPDATE but still recording log", userId);
        }
        boolean logInserted = commissionRepository.insertUserLevelLog(
                userId,
                currentV,
                targetV,
                direction.toUpperCase(Locale.ROOT),
                operator,
                null,
                idempotencyKey.trim(),
                "VRANK-OVERRIDE-" + userId + "-" + currentV + "-" + targetV + "-" + UUID.randomUUID().toString().substring(0, 8),
                true);
        if (!logInserted) {
            log.error("V-Rank override level log insert FAILED for user {} {} → {}", userId, currentV, targetV);
        }
        // promote 方向调派发器(越级派奖,MANUAL ctx);rollback 不派奖不剥夺(保留已派发流水)
        if ("promote".equals(direction)) {
            try {
                VRankPromotionContext ctx = new VRankPromotionContext(
                        userId,
                        VRankPromotionContext.TriggerType.MANUAL_OPERATION,
                        idempotencyKey.trim(),
                        operator);
                vRankRewardDispatcher.dispatch(userId, targetV, VRankEvaluationSnapshot.empty(), ctx);
            } catch (RuntimeException ex) {
                log.error("V-Rank override reward dispatch FAILED, dispatch rollback follows upstream @Transactional: user={} {} → {}: {}",
                        userId, currentV, targetV, ex.getMessage());
                throw ex;
            }
            // Sprint4: 手动晋升也发事件 → Consumer 级联 L1 上级 re-eval
            vRankPromotionEngine.publishPromotionCompleted(userId, currentV, targetV);
        }
        audit("F_TEAM_VRANK_OVERRIDDEN", "nx_team_member:" + userId + ".v_rank", operator, Map.of(
                "userId", userId,
                "fromCode", currentV,
                "toCode", targetV,
                "direction", direction,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        // 联动预览:从 nx_v_rank_config 读 before/after 的 unilevelDepth/peerBonus/votes
        Map<String, Object> beforePreview = vRankPreview(currentV);
        Map<String, Object> afterPreview = vRankPreview(targetV);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("before", currentV);
        response.put("after", targetV);
        response.put("direction", direction);
        response.put("updated", updated);
        response.put("logInserted", logInserted);
        response.put("beforePreview", beforePreview);
        response.put("afterPreview", afterPreview);
        response.put("source", "nx_team_member + nx_user_level_log + nx_v_rank_config");
        return ApiResult.ok(response);
    }

    /**
     * 端点 3:V-Rank 晋升流水查询(nx_user_level_log WHERE level_type='VRANK')。
     */
    public ApiResult<Map<String, Object>> queryPromotionLog(VRankPromotionLogQuery query) {
        Long userId = query == null ? null : query.userId();
        String v = normalizeVRankCode(query == null ? null : query.v());
        String cohort = query == null ? null : trimToNull(query.cohort());
        String from = query == null ? null : trimToNull(query.from());
        String to = query == null ? null : trimToNull(query.to());
        List<Map<String, Object>> rows = commissionRepository.queryPromotionLog(userId, v, cohort, from, to).stream()
                .map(this::normalizePromotionLogRow)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F1");
        response.put("filters", Map.of(
                "userId", userId == null ? "" : userId,
                "v", v == null ? "" : v,
                "cohort", cohort == null ? "" : cohort,
                "from", from == null ? "" : from,
                "to", to == null ? "" : to));
        response.put("total", rows.size());
        response.put("limit", 100);
        response.put("items", rows);
        response.put("source", "nx_user_level_log LEFT JOIN nx_user + nx_janus_device");
        return ApiResult.ok(response);
    }

    /** 读 nx_v_rank_config 单阶联动字段(unilevelDepth/peerBonus/votes)。Sprint6 扩展:VRankConfigRow 已携带 3 字段。 */
    private Map<String, Object> vRankPreview(String rankCode) {
        VRankConfigRow row = commissionRepository.vRankConfigRows().stream()
                .filter(r -> rankCode != null && rankCode.equalsIgnoreCase(r.rankCode()))
                .findFirst()
                .orElse(null);
        Map<String, Object> preview = new LinkedHashMap<>();
        if (row == null) {
            preview.put("rankCode", rankCode);
            preview.put("unilevelDepth", null);
            preview.put("peerBonusRate", null);
            preview.put("votes", null);
            return preview;
        }
        preview.put("rankCode", row.rankCode());
        preview.put("unilevelDepth", row.unilevelDepth());
        preview.put("peerBonusRate", row.peerBonusRate());
        preview.put("votes", row.leadershipVotes());
        preview.put("source", "nx_v_rank_config.unilevel_depth/peer_bonus_rate/leadership_votes");
        return preview;
    }

    /** "V3" → 索引 3;非法 → 0。 */
    private int vRankIndex(String rankCode) {
        if (!StringUtils.hasText(rankCode)) {
            return 0;
        }
        String upper = rankCode.trim().toUpperCase(Locale.ROOT);
        if (!upper.startsWith("V")) {
            return 0;
        }
        try {
            int idx = Integer.parseInt(upper.substring(1));
            return (idx >= 0 && idx <= 12) ? idx : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 归一化 V 阶代码(大写);空字符串 → null;非法 → 原样大写(由 SQL 自然无命中)。 */
    private String normalizeVRankCode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 解析 operator:有值用值,缺省用上下文 admin id 或 "MANUAL"。 */
    private String resolveOperator(String fallback) {
        Long adminId = parseAdminIdFromContext();
        if (adminId != null) {
            return "admin-" + adminId;
        }
        return fallback;
    }

    private String resolveOperator(String fallback, String explicit) {
        if (StringUtils.hasText(explicit)) {
            return explicit.trim();
        }
        return resolveOperator(fallback);
    }

    /** normalize promotion-log row:补全字段 + 统一 isManual 为 Boolean。 */
    private Map<String, Object> normalizePromotionLogRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", raw.getOrDefault("id", ""));
        row.put("userId", raw.getOrDefault("userId", ""));
        row.put("fromCode", textValue(raw, "fromCode", ""));
        row.put("toCode", textValue(raw, "toCode", ""));
        row.put("reason", textValue(raw, "reason", ""));
        row.put("operator", textValue(raw, "operator", ""));
        row.put("isManual", boolValue(raw.get("isManual"), false));
        row.put("cohort", raw.getOrDefault("cohort", ""));
        row.put("nickname", textValue(raw, "nickname", ""));
        row.put("createdAt", textValue(raw, "createdAt", ""));
        return row;
    }

    // ============================================================
    // Sprint 6:F1 V-Rank 派发流水查询/补发/撤销 HTTP 端点服务方法(3 个)
    // ============================================================

    /**
     * 端点 4:F1-MD4 派发流水查询。
     *
     * <p>查 nx_v_rank_reward_payout WHERE is_deleted=0 ORDER BY granted_at DESC LIMIT 100。
     * 支持 type/v/status/userId/cursor 五维筛选。
     */
    public ApiResult<Map<String, Object>> queryRewardPayouts(String type,
                                                             String v,
                                                             String status,
                                                             Long userId,
                                                             String cursor) {
        String normType = trimToNull(type);
        String normV = normalizeVRankCode(v);
        String normStatus = trimToNull(status) == null ? null : trimToNull(status).toUpperCase(Locale.ROOT);
        String normCursor = trimToNull(cursor);
        List<Map<String, Object>> rows = commissionRepository.queryRewardPayouts(normType, normV, normStatus, userId, normCursor).stream()
                .map(this::normalizeRewardPayoutRow)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F1");
        response.put("filters", Map.of(
                "type", normType == null ? "" : normType,
                "v", normV == null ? "" : normV,
                "status", normStatus == null ? "" : normStatus,
                "userId", userId == null ? "" : userId,
                "cursor", normCursor == null ? "" : normCursor));
        response.put("total", rows.size());
        response.put("limit", 100);
        // nextCursor = 末尾行的 grantedAt(供前端下一页查询;无数据=null)
        String nextCursor = rows.isEmpty() ? null : String.valueOf(rows.get(rows.size() - 1).get("grantedAt"));
        response.put("nextCursor", nextCursor == null ? "" : nextCursor);
        response.put("items", rows);
        response.put("source", "nx_v_rank_reward_payout");
        return ApiResult.ok(response);
    }

    /**
     * 端点 5:F1-MD4 补发(找原 payout → 重派)。
     *
     * <p>流程:① requireCommand ② 找原 payout(by payout_id,不存在 → VALIDATION_FAILED PAYOUT_NOT_FOUND)
     * ③ 资金类(usdt/nex)走 B1 预检(coverageFacade.snapshot belowRedline → 422 COVERAGE_BELOW_REDLINE)
     *   + 新 commission_event + TreasuryLedgerPostingFacade.postLedgerEntry(IN/PENDING);
     *   权益类(voucher/sku/custom)不进 D4,直接 UPDATE payout。
     * ④ UPDATE payout status=REISSUED + operator + reason ⑤ audit F_TEAM_REWARD_REISSUED
     *
     * <p>幂等保障:原 payout 状态非 REVERSED/REISSUED 才允许补发(防止重复补发)。
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> reissueRewardPayout(String payoutId,
                                                              String idempotencyKey,
                                                              VRankRewardPayoutActionRequest request) {
        if (!StringUtils.hasText(payoutId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PAYOUT_ID_REQUIRED");
        }
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey,
                request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Map<String, Object> original = commissionRepository.findRewardPayoutByPayoutId(payoutId.trim());
        if (original == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PAYOUT_NOT_FOUND");
        }
        String currentStatus = String.valueOf(original.getOrDefault("status", "")).toUpperCase(Locale.ROOT);
        // 幂等:仅 REVERSED 状态允许补发(已 REVERSED 的才需补发;GRANTED 已派发,REISSUED 已补发,均拒)
        if (!"REVERSED".equals(currentStatus)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "PAYOUT_REISSUE_REQUIRES_REVERSED: current=" + currentStatus);
        }
        String rewardType = String.valueOf(original.getOrDefault("rewardType", "")).toLowerCase(Locale.ROOT);
        Long userId = parseLongFromMap(original.get("userId"));
        String rankCode = String.valueOf(original.getOrDefault("rankCode", ""));
        BigDecimal amount = decimalValue(original.get("amount"), BigDecimal.ZERO);
        Long sponsorUserId = parseLongFromMap(original.get("sponsorUserId"));
        String operator = resolveOperator("MANUAL", request == null ? null : request.operator());
        String reason = request.reason().trim();

        // 资金类(usdt/nex)B1 预检 + 新 commission_event + ledgerPostingFacade.postLedgerEntry
        // 权益类(voucher/sku/custom)不走 D4,仅 UPDATE payout 状态
        if ("usdt".equals(rewardType) || "nex".equals(rewardType)) {
            if (amount.compareTo(BigDecimal.ZERO) > 0 && coverageBelowRedline()) {
                return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            }
            String currency = "usdt".equals(rewardType) ? "USDT" : "NEX";
            Long recipientUserId = sponsorUserId != null ? sponsorUserId : userId;
            Long sourceUserId = sponsorUserId != null ? userId : null;
            String commissionType = sponsorUserId != null ? "cultivation" : "vrank_reward";
            BigDecimal amountUsdt = "USDT".equals(currency) ? amount : BigDecimal.ZERO;
            BigDecimal amountNex = "NEX".equals(currency) ? amount : BigDecimal.ZERO;
            String remark = "F1 V-Rank reward REISSUE | rank=" + rankCode + " type=" + rewardType + " operator=" + operator;
            Long newCommissionEventId = commissionRepository.insertCommissionEvent(
                    recipientUserId, commissionType, sourceUserId, currency,
                    amountUsdt, amountNex, "PENDING", resolveCommissionCoolingDays(), remark);
            if (newCommissionEventId != null) {
                String billId = "F1-VRANKREWARD-REISSUE-" + newCommissionEventId;
                ledgerPostingFacade.postLedgerEntry(
                        billId, recipientUserId, "TEAM_COMMISSION", currency,
                        "IN", amount, "PENDING", remark);
            }
        }

        boolean updated = commissionRepository.updateRewardPayoutStatus(
                payoutId.trim(), "REISSUED", operator, "[REISSUE] " + reason);
        if (!updated) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PAYOUT_UPDATE_FAILED");
        }
        audit("F_TEAM_REWARD_REISSUED", "nx_v_rank_reward_payout:" + payoutId, operator, Map.of(
                "payoutId", payoutId,
                "userId", userId,
                "rankCode", rankCode,
                "rewardType", rewardType,
                "amount", amount,
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payoutId", payoutId);
        response.put("previousStatus", currentStatus);
        response.put("status", "REISSUED");
        response.put("rewardType", rewardType);
        response.put("rankCode", rankCode);
        response.put("amount", amount);
        response.put("source", "nx_v_rank_reward_payout + nx_commission_event (reissue)");
        return ApiResult.ok(response);
    }

    /**
     * 端点 6:F1-MD4 撤销(红冲反向)。
     *
     * <p>流程:① requireCommand ② 找原 payout(by payout_id,不存在 → PAYOUT_NOT_FOUND)
     * ③ 仅 GRANTED/REISSUED/PENDING_GRANT 允许撤销(REVERSED 已撤销 → 拒)④ UPDATE payout status=REVERSED + reversed_at + operator + reason
     * ⑤ 资金类 D4 红冲:UPDATE nx_commission_event.status='REVERSED'(对应 commission_event_id)
     *   + ledgerPostingFacade.postLedgerEntry(OUT/SUCCESS 反向冲正,对齐 postCommissionLedgerIfStatusChanged 范式)
     * ⑥ audit F_TEAM_REWARD_REVERSED
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> reverseRewardPayout(String payoutId,
                                                              String idempotencyKey,
                                                              VRankRewardPayoutActionRequest request) {
        if (!StringUtils.hasText(payoutId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PAYOUT_ID_REQUIRED");
        }
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey,
                request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Map<String, Object> original = commissionRepository.findRewardPayoutByPayoutId(payoutId.trim());
        if (original == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PAYOUT_NOT_FOUND");
        }
        String currentStatus = String.valueOf(original.getOrDefault("status", "")).toUpperCase(Locale.ROOT);
        // 状态机:仅 GRANTED/REISSUED/PENDING_GRANT 允许 → REVERSED;REVERSED 已终态
        if ("REVERSED".equals(currentStatus)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "PAYOUT_ALREADY_REVERSED");
        }
        String rewardType = String.valueOf(original.getOrDefault("rewardType", "")).toLowerCase(Locale.ROOT);
        Long userId = parseLongFromMap(original.get("userId"));
        Long sponsorUserId = parseLongFromMap(original.get("sponsorUserId"));
        Long commissionEventId = parseLongFromMap(original.get("commissionEventId"));
        BigDecimal amount = decimalValue(original.get("amount"), BigDecimal.ZERO);
        String rankCode = String.valueOf(original.getOrDefault("rankCode", ""));
        String operator = resolveOperator("MANUAL", request == null ? null : request.operator());
        String reason = request.reason().trim();

        // 资金类 D4 红冲:UPDATE nx_commission_event.status='REVERSED' + ledgerPostingFacade 反向 OUT 条目
        // 权益类(voucher/sku/custom)无 commission_event_id,跳过 D4
        if (("usdt".equals(rewardType) || "nex".equals(rewardType)) && commissionEventId != null) {
            int reversed = commissionRepository.reverseCommissionEvent(commissionEventId);
            if (reversed == 0) {
                log.warn("Payout reverse: commission_event {} not found or already reversed, payout={}",
                        commissionEventId, payoutId);
            }
            String currency = "usdt".equals(rewardType) ? "USDT" : "NEX";
            Long recipientUserId = sponsorUserId != null ? sponsorUserId : userId;
            String billId = "F1-VRANKREWARD-REVERSE-" + commissionEventId;
            // OUT/SUCCESS 反向冲正(对齐 postCommissionLedgerIfStatusChanged 的 REJECTED 路径)
            ledgerPostingFacade.postLedgerEntry(
                    billId, recipientUserId, "TEAM_COMMISSION", currency,
                    "OUT", amount, "SUCCESS",
                    "F1 V-Rank reward REVERSE | payout=" + payoutId + " eventId=" + commissionEventId);
        }

        boolean updated = commissionRepository.updateRewardPayoutStatus(
                payoutId.trim(), "REVERSED", operator, "[REVERSE] " + reason);
        if (!updated) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PAYOUT_UPDATE_FAILED");
        }
        audit("F_TEAM_REWARD_REVERSED", "nx_v_rank_reward_payout:" + payoutId, operator, Map.of(
                "payoutId", payoutId,
                "userId", userId,
                "rankCode", rankCode,
                "rewardType", rewardType,
                "amount", amount,
                "commissionEventId", commissionEventId == null ? "" : commissionEventId,
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payoutId", payoutId);
        response.put("previousStatus", currentStatus);
        response.put("status", "REVERSED");
        response.put("rewardType", rewardType);
        response.put("rankCode", rankCode);
        response.put("commissionEventId", commissionEventId == null ? "" : commissionEventId);
        response.put("amount", amount);
        response.put("source", "nx_v_rank_reward_payout + nx_commission_event (reverse)");
        return ApiResult.ok(response);
    }

    /** normalize payout row:字段补全 + status 大写。 */
    private Map<String, Object> normalizeRewardPayoutRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("payoutId", textValue(raw, "payoutId", ""));
        row.put("userId", raw.getOrDefault("userId", ""));
        row.put("rankCode", textValue(raw, "rankCode", ""));
        row.put("rewardType", textValue(raw, "rewardType", ""));
        row.put("amount", decimalValue(raw.get("amount"), BigDecimal.ZERO));
        row.put("voucherId", textValue(raw, "voucherId", ""));
        row.put("skuId", textValue(raw, "skuId", ""));
        row.put("customLabel", textValue(raw, "customLabel", ""));
        row.put("sponsorUserId", raw.getOrDefault("sponsorUserId", ""));
        row.put("status", textValue(raw, "status", "").toUpperCase(Locale.ROOT));
        row.put("commissionEventId", raw.getOrDefault("commissionEventId", ""));
        row.put("billId", textValue(raw, "billId", ""));
        row.put("triggerEventId", textValue(raw, "triggerEventId", ""));
        row.put("operator", textValue(raw, "operator", ""));
        row.put("reason", textValue(raw, "reason", ""));
        row.put("grantedAt", textValue(raw, "grantedAt", ""));
        row.put("reversedAt", textValue(raw, "reversedAt", ""));
        return row;
    }

    /** 从 Map 取 Long(null 安全)。 */
    private Long parseLongFromMap(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            String text = String.valueOf(raw).trim();
            return text.isEmpty() ? null : Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
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

    @Override
    public String domain() {
        return "F";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            // f_config: polymorphic ACTIVE_KEYS,key 分发(directRoyaltyPct/binary-rate/pool-ratio 等数值政策)。
            // amplifies true: royalty/match/pool 放大佣金流出。
            case "f_config" -> {
                TeamCommissionConfigUpdateRequest req = new TeamCommissionConfigUpdateRequest(str(p, "key"), str(p, "value"), reason, operator);
                return updateConfig(idem, req);
            }
            // f_ui_config: polymorphic UI keys,key 分发(F.binary.spillover/F.fulfillment... 等 toggle)。amplifies false。
            case "f_ui_config" -> {
                String key = str(p, "key");
                TeamCommissionConfigUpdateRequest req = new TeamCommissionConfigUpdateRequest(key, str(p, "value"), reason, operator);
                return updateUiConfig(idem, req, key);
            }
            // f_unilevel_rule: key 承载 layerNo + 字段(F.unilevel.L{n} usdtRate / F.unilevel.nex.L{n} nexPerUsd)。amplifies true(费率)。
            case "f_unilevel_rule" -> {
                String key = str(p, "key");
                TeamCommissionConfigUpdateRequest req = new TeamCommissionConfigUpdateRequest(key, str(p, "value"), reason, operator);
                return updateUnilevelRule(idem, req, key);
            }
            // f_commission_status: F5 动资金 → updateCommissionEventStatus → postCommissionLedgerIfStatusChanged → postLedgerEntry。
            // amplifies true(解锁放大可提余额)。A2ReplayContext 跳锁后执行完整 postLedgerEntry 资金路径,无 @T(自调用代理被绕过,靠 decide() 外层兜底)。
            case "f_commission_status" -> {
                String key = str(p, "key");
                TeamCommissionConfigUpdateRequest req = new TeamCommissionConfigUpdateRequest(key, str(p, "value"), reason, operator);
                String eventId = commissionStatusEventId(key).orElse("");
                return updateCommissionEventStatus(idem, req, key, eventId);
            }
            default -> {
                return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
            }
        }
    }

    /** 从 replay params 取字符串,null 安全。 */
    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** 从 replay params 取 Boolean,null 安全。 */
    private static Boolean boolVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return null;
    }

}
