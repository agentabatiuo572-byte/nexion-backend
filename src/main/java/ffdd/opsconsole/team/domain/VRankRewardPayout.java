package ffdd.opsconsole.team.domain;

import java.math.BigDecimal;

/**
 * F1 V-Rank 奖励派发流水(写模型 VO)。
 *
 * <p>对应 {@code nx_v_rank_reward_payout} 一行,由 {@code VRankRewardDispatcher} 写入,
 * 记录每次晋升派发的实际接收方、金额、关联 D4 台账事件与状态。
 *
 * <ul>
 *   <li>{@link #userId} — 被晋升用户(触发派发的主体)。</li>
 *   <li>{@link #sponsorUserId} — 实际接收方;培育类 NEX 为 L1 上线 sponsor_user_id,
 *       其他类型为 null(本人接收,user_id 即接收方)。</li>
 *   <li>{@link #commissionEventId} — 关联 nx_commission_event.id;资金类(usdt/培育 nex)必填,
 *       voucher/sku/custom 为 null(不入 D4 台账)。</li>
 *   <li>{@link #billId} — D4 台账 bizNo({@code F1-VRANKREWARD-<commissionEventId>});非资金类为 null。</li>
 *   <li>{@link #status} — GRANTED(已派发,资金/custom/H7 voucher)/ PENDING_GRANT(E 域 SKU 待接入)/
 *       REVERSED / REISSUED。</li>
 * </ul>
 */
public record VRankRewardPayout(
        String payoutId,
        Long userId,
        String rankCode,
        String rewardType,
        BigDecimal amount,
        String voucherId,
        String skuId,
        String customLabel,
        Long sponsorUserId,
        String status,
        Long commissionEventId,
        String billId,
        String triggerEventId,
        String operator,
        String reason) {
}
