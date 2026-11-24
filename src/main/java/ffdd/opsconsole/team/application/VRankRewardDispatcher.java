package ffdd.opsconsole.team.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantCommand;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantResult;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import ffdd.opsconsole.team.domain.VRankRewardPayout;
import ffdd.opsconsole.team.domain.VRankRewardRuleRow;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * F1 V-Rank 奖励派发器(Sprint 3)。
 *
 * <p>由 {@link VRankPromotionEngine} 在阶变化(UPDATE v_rank + INSERT level_log)成功后调用,
 * 同处 {@code @Transactional} 内 — 任一派发步骤抛异常 → 整个晋升回滚(B1 阻断/插入失败/红冲失败均回滚)。
 *
 * <h2>派发口径(按 reward_type)</h2>
 * <ul>
 *   <li><b>usdt(本人 USDT)</b> — ① B1 备付金红线预检(抛 COVERAGE_BELOW_REDLINE 回滚整笔晋升);
 *       ② INSERT nx_commission_event(commission_type='vrank_reward',currency='USDT',user_id=被晋升人);
 *       ③ ledgerPostingFacade.postLedgerEntry(direction='IN',status='PENDING',bizType='TEAM_COMMISSION');
 *       ④ INSERT nx_v_rank_reward_payout(status='GRANTED',commission_event_id,bill_id)。</li>
 *   <li><b>uvt</b> — 派给晋级本人，沿用 USDT 的待解锁和覆盖率校验。</li>
 *   <li><b>voucher</b> — 调 H7 {@link VoucherGrantFacade} 原子写入用户券权属,随后 payout 记
 *       {@code GRANTED};H7 内部负责幂等、必达审计与 outbox,权益类不入 D4。</li>
 *   <li><b>sku</b> — 原子创建 PENDING_GRANT payout 与 PENDING 履约队列；后台消费者库存预占、
 *       写用户 SKU 权益后才 CAS 两边为 GRANTED/FULFILLED，失败保持待授予并重试。</li>
 *   <li><b>custom</b> — payout INSERT only,status='GRANTED',不入 D4(无资金流转移)。</li>
 * </ul>
 *
 * <h2>幂等</h2>
 * <ul>
 *   <li>DB 层:UNIQUE(user_id, rank_code, reward_type) 防同阶多次派发。</li>
 *   <li>应用层:dispatch 入口对每条 rule 先做 existsVRankRewardPayout 检查,已派发则跳过。</li>
 *   <li>组合效果:同一用户同一阶的同一类型奖励,无论评估多少次,只派发一次。</li>
 * </ul>
 *
 * <h2>B1 备付金红线联动(D4)</h2>
 * <pre>
 * coverageFacade.snapshot() → 覆盖率可靠且达到红线?
 *   否 → 抛 BizException(422, "COVERAGE_BELOW_REDLINE") → @Transactional 回滚
 *                                                   → 整笔晋升(v_rank UPDATE + level_log + 前序 payout)全部撤销
 *   是 → 继续 INSERT commission_event + postLedgerEntry
 * </pre>
 *
 * <p><b>TODO Sprint4+:</b> 晋升事件发布(outbox / RocketMQ 通知下游域)。
 */
@ApplicationService
@RequiredArgsConstructor
@Slf4j
public class VRankRewardDispatcher {

    /** 资金类奖励币种(USDT/UVT → D4 台账方向一致,仅 currency 字段区分)。 */
    private static final String CURRENCY_USDT = "USDT";
    private static final String CURRENCY_NEX = "UVT";

    /** commission_event.commission_type — 本人奖励 / 培育类给 sponsor。 */
    private static final String COMMISSION_TYPE_VRANK_REWARD = "vrank_reward";

    /** payout.status 取值。 */
    private static final String PAYOUT_STATUS_GRANTED = "GRANTED";
    private static final String PAYOUT_STATUS_PENDING_GRANT = "PENDING_GRANT";

    /** D4 台账常量(对齐 OpsTeamService.postCommissionLedgerIfStatusChanged 范式)。 */
    private static final String LEDGER_BIZ_TYPE = "TEAM_COMMISSION";
    private static final String LEDGER_DIRECTION_IN = "IN";
    private static final String LEDGER_STATUS_PENDING = "PENDING";

    /** F5 coolingDays 配置 key(PRD line231 默认30;读 commission/cooling-days)。 */
    private static final String CONFIG_KEY_COOLING_DAYS = "commission/cooling-days";
    private static final int DEFAULT_COOLING_DAYS = 30;

    /** reward_type 白名单(对齐 OpsTeamService.VRANK_REWARD_TYPES)。 */
    private static final String REWARD_TYPE_USDT = "usdt";
    private static final String REWARD_TYPE_NEX = "uvt";
    private static final String REWARD_TYPE_VOUCHER = "voucher";
    private static final String REWARD_TYPE_SKU = "sku";
    private static final String REWARD_TYPE_CUSTOM = "custom";

    private final TeamCommissionRepository commissionRepository;
    private final TreasuryCoverageFacade coverageFacade;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final VoucherGrantFacade voucherGrantFacade;
    private final PlatformConfigFacade configFacade;

    /**
     * 派发指定阶的全部奖励规则。
     *
     * <p>本方法无独立 {@code @Transactional} — 由 {@link VRankPromotionEngine#evaluate}
     * 的 {@code @Transactional} 包裹,与 v_rank UPDATE / level_log INSERT 同事务。
     * 任一 rule 派发失败(B1 阻断/INSERT 异常)→ 整笔晋升回滚。
     *
     * @param userId   被晋升用户(触发派发的主体)
     * @param rankCode 新阶代码(如 "V3")
     * @param snapshot 评估快照(可选,用于审计备注扩展)
     * @param ctx      晋升上下文(提供 triggerEventId / operator)
     */
    public void dispatch(Long userId,
                         String rankCode,
                         VRankEvaluationSnapshot snapshot,
                         VRankPromotionContext ctx) {
        if (userId == null || !StringUtils.hasText(rankCode)) {
            log.warn("VRankRewardDispatcher.dispatch skipped: userId={}, rankCode={}", userId, rankCode);
            return;
        }

        List<VRankRewardRuleRow> rules = commissionRepository.selectVRankRewardRulesByRank(rankCode);
        if (rules.isEmpty()) {
            log.debug("No V-Rank reward rules for rank {}, skip dispatch for user {}", rankCode, userId);
            return;
        }

        String operator = ctx == null || ctx.operator() == null ? "ENGINE" : ctx.operator();
        String triggerEventId = ctx == null ? null : ctx.findSourceEventId().orElse(null);
        int dispatchedCount = 0;

        for (VRankRewardRuleRow rule : rules) {
            String rewardType = normalizeType(rule.rewardType());
            // 幂等防重:同人同阶同类型已派发过 → 跳过(防多次评估重复派发)
            if (commissionRepository.existsVRankRewardPayout(userId, rankCode, rewardType)) {
                log.info("V-Rank reward already dispatched, skip: user={}, rank={}, type={}",
                        userId, rankCode, rewardType);
                continue;
            }

            try {
                dispatchSingle(userId, rankCode, rule, rewardType, operator, triggerEventId);
                dispatchedCount++;
            } catch (IllegalStateException ex) {
                // B1 红线阻断:向上抛出 → 整笔晋升回滚
                log.warn("V-Rank reward dispatch BLOCKED by redline, rolling back promotion: user={}, rank={}, type={}, err={}",
                        userId, rankCode, rewardType, ex.getMessage());
                throw ex;
            } catch (RuntimeException ex) {
                // 其他派发异常:记录但继续后续 rule(避免一条规则失败阻塞同阶其他奖励)
                // 注意:DB UNIQUE 冲突等数据层异常会向上传播(等同回滚),此处主要吞住非关键 stub 异常
                log.error("V-Rank reward dispatch FAILED for user={}, rank={}, type={}: {}",
                        userId, rankCode, rewardType, ex.getMessage(), ex);
                throw ex;
            }
        }

        if (dispatchedCount > 0) {
            log.info("V-Rank reward dispatched: user={}, rank={}, rules={}, dispatched={}",
                    userId, rankCode, rules.size(), dispatchedCount);
        }
    }

    /**
     * 单条规则派发:按 reward_type 路由到资金/voucher/sku/custom 分支。
     */
    private void dispatchSingle(Long userId,
                                String rankCode,
                                VRankRewardRuleRow rule,
                                String rewardType,
                                String operator,
                                String triggerEventId) {
        switch (rewardType) {
            case REWARD_TYPE_USDT -> dispatchFundReward(userId, rankCode, rule, rewardType,
                    CURRENCY_USDT, operator, triggerEventId);
            case REWARD_TYPE_NEX -> dispatchFundReward(userId, rankCode, rule, rewardType,
                    CURRENCY_NEX, operator, triggerEventId);
            case REWARD_TYPE_VOUCHER -> dispatchVoucher(userId, rankCode, rule, operator, triggerEventId);
            case REWARD_TYPE_SKU -> dispatchSku(userId, rankCode, rule, operator, triggerEventId);
            case REWARD_TYPE_CUSTOM -> dispatchCustom(userId, rankCode, rule, operator, triggerEventId);
            default -> log.warn("Unknown V-Rank reward type '{}', skip: user={}, rank={}",
                    rewardType, userId, rankCode);
        }
    }

    // ============================================================
    // 资金类(USDT/UVT)→ B1 预检 + INSERT commission_event + postLedgerEntry + INSERT payout
    // ============================================================

    private void dispatchFundReward(Long userId,
                                    String rankCode,
                                    VRankRewardRuleRow rule,
                                    String rewardType,
                                    String currency,
                                    String operator,
                                    String triggerEventId) {
        BigDecimal amount = rule.amount() == null ? BigDecimal.ZERO : rule.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("V-Rank fund reward amount <= 0, skip: user={}, rank={}, type={}, amount={}",
                    userId, rankCode, rewardType, amount);
            return;
        }

        // ① B1 备付金红线预检(资金类放大平台资金流出,低于红线直接拒,整笔晋升回滚)
        assertCoverageAboveRedline(userId, rankCode, rewardType, amount);

        BigDecimal amountUsdt = CURRENCY_USDT.equals(currency) ? amount : BigDecimal.ZERO;
        BigDecimal amountNex = CURRENCY_NEX.equals(currency) ? amount : BigDecimal.ZERO;
        String remark = buildCommissionRemark(rankCode, rewardType, operator);
        Long commissionEventId = commissionRepository.insertCommissionEvent(
                userId,
                COMMISSION_TYPE_VRANK_REWARD,
                null,
                currency,
                amountUsdt,
                amountNex,
                LEDGER_STATUS_PENDING,
                resolveCoolingDays(),
                remark);
        if (commissionEventId == null) {
            throw new IllegalStateException("COMMISSION_EVENT_INSERT_FAILED: user=" + userId
                    + ", rank=" + rankCode + ", type=" + rewardType);
        }

        String billId = buildBillId(commissionEventId);
        ledgerPostingFacade.postLedgerEntry(
                billId, userId, LEDGER_BIZ_TYPE, currency, LEDGER_DIRECTION_IN, amount,
                LEDGER_STATUS_PENDING, "F1 V-Rank reward payout | " + remark);

        // ⑤ INSERT nx_v_rank_reward_payout(status='GRANTED', commission_event_id, bill_id)
        VRankRewardPayout payout = new VRankRewardPayout(
                generatePayoutId(userId, rankCode, rewardType),
                userId,
                rankCode,
                rewardType,
                amount,
                null,
                null,
                rule.customLabel(),
                null,
                PAYOUT_STATUS_GRANTED,
                commissionEventId,
                billId,
                triggerEventId,
                operator,
                remark);
        boolean inserted = commissionRepository.insertVRankRewardPayout(payout);
        if (!inserted) {
            throw new IllegalStateException("PAYOUT_INSERT_FAILED: user=" + userId
                    + ", rank=" + rankCode + ", type=" + rewardType);
        }
        log.info("V-Rank fund reward GRANTED: user={}, rank={}, type={}, amount={}, commissionEventId={}, billId={}",
                userId, rankCode, rewardType, amount, commissionEventId, billId);
    }

    private void assertCoverageAboveRedline(Long userId, String rankCode, String rewardType, BigDecimal amount) {
        String block = BinarySettlementPolicyProvider.currentCoverageBlock(coverageFacade.snapshot());
        if ("B1_COVERAGE_UNRELIABLE".equals(block)) {
            throw new BizException(OpsErrorCode.COVERAGE_UNAVAILABLE.httpStatus(),
                    OpsErrorCode.COVERAGE_UNAVAILABLE.name());
        }
        if (block != null) {
            log.warn("B1 coverage below redline, BLOCK V-Rank fund reward: user={}, rank={}, type={}, amount={}",
                    userId, rankCode, rewardType, amount);
            throw new BizException(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
    }

    // ============================================================
    // voucher → H7 真实权属;sku 仍待 E 域接入
    // ============================================================

    /**
     * H7 voucher grant 与 F1 payout 同处晋升事务。任一审计/outbox/payout 写失败均回滚券权属。
     */
    private void dispatchVoucher(Long userId,
                                 String rankCode,
                                 VRankRewardRuleRow rule,
                                 String operator,
                                 String triggerEventId) {
        if (!StringUtils.hasText(rule.voucherId())) {
            throw new IllegalStateException("H7_VOUCHER_ID_REQUIRED");
        }
        String voucherId = rule.voucherId().trim();
        String sourceId = userId + ":" + rankCode + ":voucher";
        String grantKey = "vrank:" + userId + ":" + rankCode.toLowerCase(Locale.ROOT) + ":" + voucherId;
        VoucherGrantResult grant = voucherGrantFacade.grant(new VoucherGrantCommand(
                userId,
                voucherId,
                grantKey,
                "VRANK_REWARD",
                sourceId,
                operator,
                "F1 V-Rank voucher reward"));

        VRankRewardPayout payout = new VRankRewardPayout(
                generatePayoutId(userId, rankCode, REWARD_TYPE_VOUCHER),
                userId,
                rankCode,
                REWARD_TYPE_VOUCHER,
                null,
                voucherId,
                null,
                rule.customLabel(),
                null,
                PAYOUT_STATUS_GRANTED,
                null,
                null,
                triggerEventId,
                operator,
                "F1 V-Rank voucher granted | h7GrantId=" + grant.grantId());
        if (!commissionRepository.insertVRankRewardPayout(payout)) {
            throw new IllegalStateException("PAYOUT_INSERT_FAILED: voucher user=" + userId + ", rank=" + rankCode);
        }
        log.info("V-Rank voucher reward GRANTED: user={}, rank={}, voucherId={}, h7GrantId={}, replayed={}",
                userId, rankCode, voucherId, grant.grantId(), grant.replayed());
    }

    /**
     * F1 券补发:按 payoutId 生成新的 H7 发放来源与幂等键,真实发券。
     * 首次发放的 source_id=userId:rankCode:voucher 已在冲正时作废;补发使用 payoutId 后缀
     * 避免唯一键冲突,同时保持 source_type='VRANK_REWARD' 便于再次冲正时定位。
     */
    public VoucherGrantResult reissueVoucherReward(Long userId, String rankCode, String voucherId,
                                                   String payoutId, String reissueKey, String operator) {
        String suffix = "reissue:" + stableRound(payoutId + ":" + reissueKey);
        String sourceId = userId + ":" + rankCode + ":voucher:" + suffix;
        String grantKey = "vrank:" + userId + ":" + rankCode.toLowerCase(Locale.ROOT) + ":" + voucherId + ":" + suffix;
        return voucherGrantFacade.grant(new VoucherGrantCommand(
                userId, voucherId, grantKey, "VRANK_REWARD", sourceId, operator,
                "F1 V-Rank voucher reissue"));
    }

    /** 每次获批补发绑定独立轮次键;同一轮重试得到同一键,回放旧发放;不同轮次得到不同键,发新券。 */
    private static String stableRound(String seed) {
        String source = StringUtils.hasText(seed) ? seed.trim() : "retry";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(source.hashCode());
        }
    }

    private void dispatchSku(Long userId,
                             String rankCode,
                             VRankRewardRuleRow rule,
                             String operator,
                             String triggerEventId) {
        if (!StringUtils.hasText(rule.skuId())) {
            throw new IllegalArgumentException("VRANK_SKU_ID_REQUIRED");
        }
        String reason = "F1 V-Rank SKU entitlement | sku=" + rule.skuId() + " | operator=" + operator;
        if (!commissionRepository.enqueueVRankSkuFulfillment(userId, rankCode, rule.skuId(), reason)) {
            throw new IllegalStateException("SKU_FULFILLMENT_ENQUEUE_FAILED");
        }
        VRankRewardPayout payout = new VRankRewardPayout(
                generatePayoutId(userId, rankCode, REWARD_TYPE_SKU),
                userId, rankCode, REWARD_TYPE_SKU, null, null, rule.skuId(), rule.customLabel(), null,
                PAYOUT_STATUS_PENDING_GRANT, null, null, triggerEventId, operator, reason);
        if (!commissionRepository.insertVRankRewardPayout(payout)) {
            throw new IllegalStateException("PAYOUT_INSERT_FAILED: sku user=" + userId + ", rank=" + rankCode);
        }
        log.info("V-Rank sku entitlement PENDING_GRANT and fulfillment queued: user={}, rank={}, skuId={}",
                userId, rankCode, rule.skuId());
    }

    /**
     * custom:仅记流水(status='GRANTED'),不入 D4(无资金流转移)。
     */
    private void dispatchCustom(Long userId,
                                String rankCode,
                                VRankRewardRuleRow rule,
                                String operator,
                                String triggerEventId) {
        VRankRewardPayout payout = new VRankRewardPayout(
                generatePayoutId(userId, rankCode, REWARD_TYPE_CUSTOM),
                userId,
                rankCode,
                REWARD_TYPE_CUSTOM,
                null,
                null,
                null,
                rule.customLabel(),
                null,
                PAYOUT_STATUS_GRANTED,
                null,
                null,
                triggerEventId,
                operator,
                "F1 V-Rank custom reward | rank=" + rankCode);
        boolean inserted = commissionRepository.insertVRankRewardPayout(payout);
        if (!inserted) {
            throw new IllegalStateException("PAYOUT_INSERT_FAILED: custom user=" + userId
                    + ", rank=" + rankCode);
        }
        log.info("V-Rank custom reward GRANTED (no D4 posting): user={}, rank={}, label={}",
                userId, rankCode, rule.customLabel());
    }

    // ============================================================
    // 辅助
    // ============================================================

    /** reward_type 归一化(小写 + 去空白)。 */
    private String normalizeType(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** F5 coolingDays(读 commission/cooling-days,默认30;PRD line231)。 */
    private int resolveCoolingDays() {
        return configFacade.activeValue(CONFIG_KEY_COOLING_DAYS)
                .map(v -> {
                    try { return Integer.parseInt(v.trim()); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(d -> d >= 0)
                .orElse(DEFAULT_COOLING_DAYS);
    }

    private String buildCommissionRemark(String rankCode, String rewardType, String operator) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rank", rankCode);
        fields.put("type", rewardType);
        fields.put("cultivation", false);
        fields.put("operator", operator);
        return "F1 V-Rank reward | " + fields;
    }

    /** D4 台账 bizNo(对齐 OpsTeamService.postCommissionLedgerIfStatusChanged 的 "F5-COMMISSION-<eventId>-<state>" 命名)。 */
    private String buildBillId(Long commissionEventId) {
        return "F1-VRANKREWARD-" + commissionEventId;
    }

    /** payout_id:V-VRANKPAYOUT-userId-rankCode-rewardType-uuid8(全局唯一)。 */
    private String generatePayoutId(Long userId, String rankCode, String rewardType) {
        return "V-VRANKPAYOUT-" + userId + "-" + rankCode + "-" + rewardType
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
