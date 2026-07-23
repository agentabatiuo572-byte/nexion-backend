package ffdd.opsconsole.team.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantCommand;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantResult;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import ffdd.opsconsole.team.domain.VRankRewardPayout;
import ffdd.opsconsole.team.domain.VRankRewardRuleRow;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
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
 *   <li><b>nex(培育类 NEX)</b> — 默认派给 L1 上线 sponsor(承接培育奖语义,PRD §8.2.5):
 *       commission_type='cultivation',user_id=sponsor,source_user_id=被晋升人;
 *       若 reward 的 custom_label 含 "self"/"direct" 标记,或 sponsor 不存在 → fallback 派本人(commission_type='vrank_reward')。
 *       其余流程同 usdt(currency='NEX')。B1 预检同 usdt。</li>
 *   <li><b>voucher</b> — 调 H7 {@link VoucherGrantFacade} 原子写入用户券权属,随后 payout 记
 *       {@code GRANTED};H7 内部负责幂等、必达审计与 outbox,权益类不入 D4。</li>
 *   <li><b>sku</b> — E 域 SkuFacade 未接入 → payout INSERT only,status='PENDING_GRANT',
 *       不入 D4。待 E 域 SkuService 接入后补 grant 调用。</li>
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
 * coverageFacade.snapshot() → coverageRatio < redlinePct?
 *   是 → 抛 IllegalStateException("COVERAGE_BELOW_REDLINE") → @Transactional 回滚
 *                                                   → 整笔晋升(v_rank UPDATE + level_log + 前序 payout)全部撤销
 *   否 → 继续 INSERT commission_event + postLedgerEntry
 * </pre>
 *
 * <p><b>TODO Sprint4+:</b> 晋升事件发布(outbox / RocketMQ 通知下游域)。
 * <p><b>TODO E 域+:</b> SkuFacade.grant 替换 sku stub。
 */
@ApplicationService
@RequiredArgsConstructor
@Slf4j
public class VRankRewardDispatcher {

    /** 资金类奖励币种(USDT/NEX → D4 台账方向一致,仅 currency 字段区分)。 */
    private static final String CURRENCY_USDT = "USDT";
    private static final String CURRENCY_NEX = "NEX";

    /** commission_event.commission_type — 本人奖励 / 培育类给 sponsor。 */
    private static final String COMMISSION_TYPE_VRANK_REWARD = "vrank_reward";
    private static final String COMMISSION_TYPE_CULTIVATION = "cultivation";

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
    private static final String REWARD_TYPE_NEX = "nex";
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
                    CURRENCY_USDT, operator, triggerEventId, false);
            case REWARD_TYPE_NEX -> dispatchNexReward(userId, rankCode, rule, operator, triggerEventId);
            case REWARD_TYPE_VOUCHER -> dispatchVoucher(userId, rankCode, rule, operator, triggerEventId);
            case REWARD_TYPE_SKU -> dispatchSkuStub(userId, rankCode, rule, operator, triggerEventId);
            case REWARD_TYPE_CUSTOM -> dispatchCustom(userId, rankCode, rule, operator, triggerEventId);
            default -> log.warn("Unknown V-Rank reward type '{}', skip: user={}, rank={}",
                    rewardType, userId, rankCode);
        }
    }

    // ============================================================
    // 资金类(USDT/NEX)→ B1 预检 + INSERT commission_event + postLedgerEntry + INSERT payout
    // ============================================================

    /**
     * 派发资金类奖励(USDT 或 培育类 NEX)。
     *
     * @param currency        USDT / NEX
     * @param isCultivation   true=培育类(派 sponsor),false=本人奖励(派本人)
     */
    private void dispatchFundReward(Long userId,
                                    String rankCode,
                                    VRankRewardRuleRow rule,
                                    String rewardType,
                                    String currency,
                                    String operator,
                                    String triggerEventId,
                                    boolean isCultivation) {
        BigDecimal amount = rule.amount() == null ? BigDecimal.ZERO : rule.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("V-Rank fund reward amount <= 0, skip: user={}, rank={}, type={}, amount={}",
                    userId, rankCode, rewardType, amount);
            return;
        }

        // ① B1 备付金红线预检(资金类放大平台资金流出,低于红线直接拒,整笔晋升回滚)
        assertCoverageAboveRedline(userId, rankCode, rewardType, amount);

        // ② 确定接收方:培育类=sponsor(fallback 本人),本人奖励=被晋升人
        Long recipientUserId = userId;
        Long sponsorUserId = null;
        Long sourceUserId = null; // commission_event.source_user_id:触发用户(被晋升人)
        String commissionType = COMMISSION_TYPE_VRANK_REWARD;
        if (isCultivation) {
            sponsorUserId = commissionRepository.findSponsorUserId(userId);
            if (sponsorUserId != null) {
                recipientUserId = sponsorUserId;
                sourceUserId = userId;
                commissionType = COMMISSION_TYPE_CULTIVATION;
            } else {
                log.info("V-Rank cultivation NEX has no sponsor for user {}, fallback to self: rank={}",
                        userId, rankCode);
                // sponsor 不存在 → fallback 派本人(commission_type 仍为 vrank_reward)
            }
        }

        // ③ INSERT nx_commission_event
        BigDecimal amountUsdt = CURRENCY_USDT.equals(currency) ? amount : BigDecimal.ZERO;
        BigDecimal amountNex = CURRENCY_NEX.equals(currency) ? amount : BigDecimal.ZERO;
        String remark = buildCommissionRemark(rankCode, rewardType, operator, isCultivation, sponsorUserId);
        Long commissionEventId = commissionRepository.insertCommissionEvent(
                recipientUserId,
                commissionType,
                sourceUserId,
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

        // ④ D4 台账 postLedgerEntry(对齐 OpsTeamService.postCommissionLedgerIfStatusChanged)
        String billId = buildBillId(commissionEventId);
        ledgerPostingFacade.postLedgerEntry(
                billId,
                recipientUserId,
                LEDGER_BIZ_TYPE,
                currency,
                LEDGER_DIRECTION_IN,
                amount,
                LEDGER_STATUS_PENDING,
                "F1 V-Rank reward payout | " + remark);

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
                sponsorUserId,
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
        log.info("V-Rank fund reward GRANTED: user={}, recipient={}, rank={}, type={}, amount={}, commissionEventId={}, billId={}",
                userId, recipientUserId, rankCode, rewardType, amount, commissionEventId, billId);
    }

    /**
     * NEX 奖励特殊路由:默认培育类(sponsor 收),custom_label 含 "self"/"direct" 或无 sponsor 时 fallback 本人。
     */
    private void dispatchNexReward(Long userId,
                                   String rankCode,
                                   VRankRewardRuleRow rule,
                                   String operator,
                                   String triggerEventId) {
        boolean isCultivation = !hasSelfMarker(rule.customLabel());
        dispatchFundReward(userId, rankCode, rule, REWARD_TYPE_NEX,
                CURRENCY_NEX, operator, triggerEventId, isCultivation);
    }

    /**
     * B1 预检:备付金覆盖率 < 红线 → 抛 COVERAGE_BELOW_REDLINE,事务回滚(整笔晋升撤销)。
     */
    private void assertCoverageAboveRedline(Long userId, String rankCode, String rewardType, BigDecimal amount) {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        BigDecimal ratio = coverage.coverageRatio() == null ? BigDecimal.ZERO : coverage.coverageRatio();
        BigDecimal redline = coverage.redlinePct() == null ? BigDecimal.ZERO : coverage.redlinePct();
        if (ratio.compareTo(redline) < 0) {
            log.warn("B1 coverage below redline, BLOCK V-Rank fund reward: user={}, rank={}, type={}, amount={}, ratio={}, redline={}",
                    userId, rankCode, rewardType, amount, ratio, redline);
            throw new IllegalStateException("COVERAGE_BELOW_REDLINE");
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
     * sku stub:SkuFacade 未接入(E 域待实现)。
     * 当前仅 INSERT payout(status='PENDING_GRANT'),不入 D4 台账。后续 E 域接入后补 grant 调用。
     */
    private void dispatchSkuStub(Long userId,
                                 String rankCode,
                                 VRankRewardRuleRow rule,
                                 String operator,
                                 String triggerEventId) {
        insertPendingGrantPayout(userId, rankCode, rule, REWARD_TYPE_SKU, operator, triggerEventId);
        log.info("V-Rank sku reward PENDING_GRANT (E-domain stub): user={}, rank={}, skuId={}",
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

    private void insertPendingGrantPayout(Long userId,
                                          String rankCode,
                                          VRankRewardRuleRow rule,
                                          String rewardType,
                                          String operator,
                                          String triggerEventId) {
        VRankRewardPayout payout = new VRankRewardPayout(
                generatePayoutId(userId, rankCode, rewardType),
                userId,
                rankCode,
                rewardType,
                null,
                rule.voucherId(),
                rule.skuId(),
                rule.customLabel(),
                null,
                PAYOUT_STATUS_PENDING_GRANT,
                null,
                null,
                triggerEventId,
                operator,
                "F1 V-Rank " + rewardType + " pending grant (stub)");
        boolean inserted = commissionRepository.insertVRankRewardPayout(payout);
        if (!inserted) {
            throw new IllegalStateException("PAYOUT_INSERT_FAILED: " + rewardType + " user=" + userId
                    + ", rank=" + rankCode);
        }
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

    /** NEX custom_label 含 "self"/"direct" → 视为"本人 NEX"(非培育,派本人)。 */
    private boolean hasSelfMarker(String customLabel) {
        if (!StringUtils.hasText(customLabel)) {
            return false;
        }
        String lower = customLabel.toLowerCase(Locale.ROOT);
        return lower.contains("self") || lower.contains("direct");
    }

    private String buildCommissionRemark(String rankCode,
                                         String rewardType,
                                         String operator,
                                         boolean isCultivation,
                                         Long sponsorUserId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rank", rankCode);
        fields.put("type", rewardType);
        fields.put("cultivation", isCultivation);
        if (sponsorUserId != null) {
            fields.put("sponsorUserId", sponsorUserId);
        }
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
