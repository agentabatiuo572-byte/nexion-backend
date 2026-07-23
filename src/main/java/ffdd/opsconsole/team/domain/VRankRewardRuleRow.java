package ffdd.opsconsole.team.domain;

import java.math.BigDecimal;

/**
 * F1 V-Rank 奖励规则行(读模型 VO)。
 *
 * <p>对应 {@code nx_v_rank_reward_rule} 一行,由 {@code VRankRewardDispatcher} 消费,
 * 决定晋升达成后的派发动作(资金类入 D4 台账 / 权益类 stub / custom 仅流水)。
 *
 * <ul>
 *   <li>{@link #rewardType} — 派发口径:{@code usdt}(本人 USDT)/
 *       {@code nex}(培育类 NEX,默认派给 L1 上线 sponsor)/
 *       {@code voucher}(H7 真实权益)/{@code sku}(权益类,stub)/
 *       {@code custom}(流水 only,不入 D4)。</li>
 *   <li>{@link #amount} — 资金类金额(usdt/nex 必填,voucher/sku/custom 为 null)。</li>
 *   <li>{@link #customLabel} — custom 类型描述;NEX 类型若包含 {@code "self"}/{@code "direct"}
 *       标记则视为"本人 NEX"(非培育),其余 NEX 默认培育派 sponsor。</li>
 * </ul>
 */
public record VRankRewardRuleRow(
        String rewardId,
        String rankCode,
        String rewardType,
        BigDecimal amount,
        String voucherId,
        String skuId,
        String customLabel,
        int sortOrder) {

    public VRankRewardRuleRow {
        if (rewardType == null || rewardType.isBlank()) {
            throw new IllegalArgumentException("rewardType must not be blank");
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
    }
}
