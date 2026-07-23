package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankConfigRow;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * F1 V-Rank 晋升引擎核心(Sprint 1+2)。
 *
 * <p>职责:评估单个用户的 v_rank 是否应晋升,逐阶判定 + 不降级保护 + 写入审计流水。
 *
 * <h2>评估算法(默认口径)</h2>
 * <ol>
 *   <li>读当前 v_rank:nx_team_member 自循环行(user_id=member_user_id=userId),默认 V0。</li>
 *   <li>读 13 阶 nx_v_rank_config(按 sort_order 升序)。</li>
 *   <li>读 F.vrank.permanent 不降级开关(configFacade "team.ui.F.vrank.permanent" = "on")。</li>
 *   <li>snapshot = performanceRepository.computeSnapshot(userId)。</li>
 *   <li>逐阶判定 reachable(while reachable&lt;12:meetsAllConditions(snapshot, ranks[i+1])?升一级:break),
 *       得到用户"应当处于"的最高阶。<b>逐阶不越级</b> — 必须先满足 V(n) 才能评估 V(n+1),
 *       与用户当前阶无关(防止"当前 V5 但 V1 条件都不满足"时仍保留 V5)。</li>
 *   <li>不降级:permanent AND reachable&lt;currentV → 维持 currentV;否则 → 移到 reachable(可能升/降/平)。</li>
 *   <li>写入(@Transactional):UPDATE nx_team_member.v_rank + INSERT nx_user_level_log。</li>
 * </ol>
 *
 * <h2>meetsAllConditions 字段规则</h2>
 * <ul>
 *   <li>字段 &gt; 0 才作为条件(缺失/null/0 视为该阶不要求此维度)。</li>
 *   <li>{@code self_buy_usd>0}:snapshot.selfBuyUSD &gt;= config。</li>
 *   <li>{@code direct_refs>0}:snapshot.directRefs &gt;= config。</li>
 *   <li>{@code team_volume_usd>0}:snapshot.teamVolumeUSD &gt;= config。</li>
 *   <li>{@code required_downline_count>0}:parse({@code required_downline_rank}) → targetV level,
 *       cumulativeLegCount(legCounts, targetV) &gt;= count。采用累加语义:
 *       L1 成员中 v_rank &gt;= targetV 的全部计入(即"达到 V{n} 阶的腿数")。
 *       例:V3 需 2 条 V2 腿 → L1 中 v_rank&gt;=V2 至少 2 条。</li>
 * </ul>
 *
 * <p><b>Sprint3 实现:</b> 奖励派发(达成 V 级 → {@link VRankRewardDispatcher#dispatch} 读 nx_v_rank_reward_rule
 * → 派发,联动 D4 台账/B1 红线/H7 voucher 权属/sku stub/custom)。
 * <p><b>TODO Sprint4+:</b> 事件发布(晋升事件 outbox / RocketMQ 通知)。
 * <p><b>TODO Sprint5+:</b> HTTP 端点暴露(@RestController 调用 evaluate)。
 */
@ApplicationService
@RequiredArgsConstructor
@Slf4j
public class VRankPromotionEngine {

    /** 配置 key:F.vrank.permanent 不降级开关("on"/"off")。 */
    private static final String CONFIG_KEY_VRANK_PERMANENT = "team.ui.F.vrank.permanent";

    /** 阶代码列表,索引对齐 sort_order(V0=0..V12=12)。 */
    private static final List<String> VRANK_CODES = List.of(
            "V0", "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10", "V11", "V12");

    private final TeamCommissionRepository commissionRepository;
    private final VRankPerformanceRepository performanceRepository;
    private final PlatformConfigFacade configFacade;
    private final ObjectMapper objectMapper;
    private final VRankRewardDispatcher rewardDispatcher;
    /** Sprint4: outbox 事件发布(晋升完成事件 → Consumer 级联 L1 上级)。 */
    private final EventOutboxService eventOutboxService;

    /**
     * 评估并(若有变更)落盘用户晋升。空操作时返回当前阶码,不写库。
     *
     * @param ctx 晋升上下文(userId 必填)
     * @return 评估后的 v_rank 阶代码(可能是当前阶或更高阶)
     */
    @Transactional(rollbackFor = Exception.class)
    public String evaluate(VRankPromotionContext ctx) {
        if (ctx == null || ctx.userId() == null) {
            throw new IllegalArgumentException("VRankPromotionContext.userId must not be null");
        }
        Long userId = ctx.userId();

        // 1. 当前阶(自循环行;不存在 → V0)
        String currentRank = commissionRepository.currentMemberVRank(userId);
        int currentIndex = rankIndex(currentRank);

        // 2. 13 阶配置(按 sort_order 升序)
        List<VRankConfigRow> ranks = commissionRepository.vRankConfigRows();
        if (ranks.isEmpty()) {
            log.warn("V-Rank config empty, skip evaluation for user {}", userId);
            return currentRank;
        }

        // 3. 不降级开关
        boolean permanent = isPermanentProtectionEnabled();

        // 4. 业绩快照
        VRankEvaluationSnapshot snapshot = performanceRepository.computeSnapshot(userId);

        // 5. 逐阶判定 reachable:从 V0 起逐阶向上(不越级,与当前阶无关)
        int reachableIndex = 0;
        while (reachableIndex < VRANK_CODES.size() - 1) {
            int targetIndex = reachableIndex + 1;
            VRankConfigRow targetRank = rankByIndex(ranks, targetIndex);
            if (targetRank == null) {
                log.debug("V-Rank index {} has no config row, stop escalation for user {}", targetIndex, userId);
                break;
            }
            if (meetsAllConditions(snapshot, targetRank)) {
                reachableIndex = targetIndex;
            } else {
                break;
            }
        }

        // 6. 不降级:permanent AND reachable<current → 维持 current;否则 → 移到 reachable
        int newIndex;
        if (permanent && reachableIndex < currentIndex) {
            log.info("Permanent protection: user {} retains {} (would have downgraded to {})",
                    userId, currentRank, VRANK_CODES.get(reachableIndex));
            newIndex = currentIndex;
        } else {
            newIndex = reachableIndex;
        }

        String newRank = VRANK_CODES.get(newIndex);
        if (newIndex == currentIndex) {
            // 无阶变化:不写流水(避免噪声)。如后续需要"评估但未变"流水,可改此处。
            log.debug("V-Rank unchanged for user {}: {}", userId, currentRank);
            return currentRank;
        }

        // 7. 写入:UPDATE nx_team_member.v_rank + INSERT nx_user_level_log
        String fromCode = currentRank;
        String toCode = newRank;
        String snapshotJson = serializeSnapshot(snapshot);
        String auditNo = generateAuditNo(userId, fromCode, toCode);
        boolean updated = commissionRepository.updateMemberVRank(userId, toCode);
        if (!updated) {
            // 自循环行不存在:V0→V1 时常见(用户从未进过 nx_team_member)。记录但不阻断 — 引擎只评估,自循环行由注册流程维护。
            log.warn("V-Rank self-loop row missing for user {}, skipping UPDATE but still recording log", userId);
        }
        boolean logInserted = commissionRepository.insertUserLevelLog(
                userId,
                fromCode,
                toCode,
                ctx.triggerType().code(),
                ctx.operator() == null ? "ENGINE" : ctx.operator(),
                snapshotJson,
                ctx.findSourceEventId().orElse(null),
                auditNo,
                ctx.triggerType() == VRankPromotionContext.TriggerType.MANUAL_OPERATION);
        if (logInserted) {
            log.info("V-Rank promoted: user {} {} → {} (trigger={}, auditNo={})",
                    userId, fromCode, toCode, ctx.triggerType(), auditNo);
        } else {
            log.error("V-Rank level log insert FAILED for user {} {} → {}", userId, fromCode, toCode);
        }

        // Sprint3 实现:奖励派发 — 读 nx_v_rank_reward_rule + 联动 D4/B1/voucher/sku/custom。
        // 同处 @Transactional:派奖失败(如 B1 红线)→ 整笔晋升回滚(v_rank UPDATE + level_log INSERT 全撤销)。
        try {
            rewardDispatcher.dispatch(userId, toCode, snapshot, ctx);
        } catch (RuntimeException ex) {
            log.error("V-Rank reward dispatch FAILED, rolling back promotion: user={} {} → {}: {}",
                    userId, fromCode, toCode, ex.getMessage());
            throw ex;
        }
        // Sprint4: 晋升成功(已落库 + 派奖)→ 发 outbox 事件,异步触发 L1 上级级联评估
        publishPromotionCompleted(userId, fromCode, toCode);

        return toCode;
    }

    /**
     * meetsAllConditions:字段 >0 才作为条件,所有作为条件的字段都满足才返回 true。
     * 字段缺失/null/0 = 不要求此维度(通过)。
     */
    boolean meetsAllConditions(VRankEvaluationSnapshot snapshot, VRankConfigRow target) {
        if (target == null) {
            return false;
        }
        // self_buy_usd > 0 → snapshot.selfBuyUSD >= config
        if (gt(target.selfBuyUsd())) {
            if (snapshot.selfBuyUSD().compareTo(target.selfBuyUsd()) < 0) {
                return false;
            }
        }
        // direct_refs > 0 → snapshot.directRefs >= config
        if (target.directRefs() > 0) {
            if (snapshot.directRefs() < target.directRefs()) {
                return false;
            }
        }
        // team_volume_usd > 0 → snapshot.teamVolumeUSD >= config
        if (gt(target.teamVolumeUsd())) {
            if (snapshot.teamVolumeUSD().compareTo(target.teamVolumeUsd()) < 0) {
                return false;
            }
        }
        // required_downline_count > 0 → legCounts[targetV] >= count
        if (target.requiredDownlineCount() > 0) {
            Integer targetV = parseRankLevel(target.requiredDownlineRank());
            if (targetV == null) {
                // 配了 count 但没配 rank:保守视为不满足(避免 0 腿误升)
                return false;
            }
            int actualLegs = cumulativeLegCount(snapshot.legCounts(), targetV);
            if (actualLegs < target.requiredDownlineCount()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 累加 legCounts 中所有 vLevel >= targetV 的成员数。
     *
     * <p>"达到 V{n} 阶的腿数"业务语义:V3 阶要求 2 条 V2 腿 — 即 L1 成员中 v_rank >= V2 的至少 2 条。
     * 故按 >= targetV 累加。
     */
    private int cumulativeLegCount(Map<Integer, Integer> legCounts, int targetV) {
        int sum = 0;
        for (Map.Entry<Integer, Integer> entry : legCounts.entrySet()) {
            if (entry.getKey() != null && entry.getKey() >= targetV) {
                sum += entry.getValue();
            }
        }
        return sum;
    }

    /** "V3" → 3;"V12" → 12;非法 → null。 */
    Integer parseRankLevel(String rankCode) {
        if (!StringUtils.hasText(rankCode)) {
            return null;
        }
        String upper = rankCode.trim().toUpperCase(Locale.ROOT);
        if (!upper.startsWith("V")) {
            return null;
        }
        try {
            int level = Integer.parseInt(upper.substring(1));
            return (level >= 0 && level <= 12) ? level : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** "V3" → 索引 3;默认 0(V0)。 */
    private int rankIndex(String rankCode) {
        Integer level = parseRankLevel(rankCode);
        return level == null ? 0 : level;
    }

    /** 索引 → 配置行(无匹配返回 null)。 */
    private VRankConfigRow rankByIndex(List<VRankConfigRow> ranks, int targetIndex) {
        if (targetIndex < 0 || targetIndex >= VRANK_CODES.size()) {
            return null;
        }
        String expectedCode = VRANK_CODES.get(targetIndex);
        return ranks.stream()
                .filter(row -> expectedCode.equalsIgnoreCase(row.rankCode()))
                .findFirst()
                .orElse(null);
    }

    /** BigDecimal > 0(剔除 null/0/负)。 */
    private boolean gt(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 读 F.vrank.permanent 开关:"on" → true(默认 off)。 */
    private boolean isPermanentProtectionEnabled() {
        Optional<String> value = configFacade.activeValue(CONFIG_KEY_VRANK_PERMANENT);
        return value.filter(StringUtils::hasText)
                .map(v -> "on".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    private String serializeSnapshot(VRankEvaluationSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("selfBuyUSD", snapshot.selfBuyUSD());
        json.put("teamVolumeUSD", snapshot.teamVolumeUSD());
        json.put("directRefs", snapshot.directRefs());
        json.put("legCounts", snapshot.legCounts());
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize V-Rank snapshot for user, storing null: {}", ex.getMessage());
            return null;
        }
    }

    /** Sprint4: V-Rank 晋升完成事件类型(Consumer 级联 L1 上级 re-eval)。 */
    static final String PROMOTION_EVENT_TYPE = "VRANK_PROMOTION_COMPLETED";

    /**
     * Sprint4: 发布晋升完成事件到 outbox → DispatchScheduler 异步分发 → Consumer 级联 L1 上级。
     * 失败不阻断晋升(v_rank + 派奖已 commit);事件丢失由 VRankPromotionScheduler 兜底补评估。
     */
    void publishPromotionCompleted(Long userId, String fromCode, String toCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("fromCode", fromCode);
        payload.put("toCode", toCode);
        try {
            eventOutboxService.publish("vrank", String.valueOf(userId), PROMOTION_EVENT_TYPE, payload);
            log.info("V-Rank promotion event published: user={} {} → {}", userId, fromCode, toCode);
        } catch (RuntimeException ex) {
            log.warn("V-Rank promotion event publish FAILED (promotion committed, scheduler backfills): user={} {} → {}: {}",
                    userId, fromCode, toCode, ex.getMessage());
        }
    }

    /** 审计序号:userId-from-to-uuid 前 8 位(单次晋升唯一)。 */
    private String generateAuditNo(Long userId, String fromCode, String toCode) {
        return "VRANK-" + userId + "-" + fromCode + "-" + toCode + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
