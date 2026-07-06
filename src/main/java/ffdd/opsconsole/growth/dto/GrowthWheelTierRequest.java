package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;

/**
 * H4 抽奖转盘档位(wheelTiers)新建请求,业务表 nx_growth_wheel_tier(目录数据)。
 * probabilityPct 0-100;realOutflow 0/1 是否真实出金;rewardKind 如 nex/usdt/voucher。
 */
public record GrowthWheelTierRequest(
        String tierName,
        String rewardName,
        BigDecimal probabilityPct,
        Integer realOutflow,
        String rewardKind,
        String reason,
        String operator) {
}
