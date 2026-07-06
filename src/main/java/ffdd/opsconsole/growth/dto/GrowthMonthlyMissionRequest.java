package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;

/**
 * H3 月度挑战(monthlyMissions)新建请求,独立表 nx_monthly_challenge。
 * monthsFrom/monthsTo 为账龄段(月),rewardType=NEX/USDT 等。
 */
public record GrowthMonthlyMissionRequest(
        String challengeCode,
        String challengeName,
        String description,
        String theme,
        Integer monthsFrom,
        Integer monthsTo,
        String targetType,
        Integer targetValue,
        String rewardType,
        BigDecimal rewardAmount,
        String rewardName,
        String reason,
        String operator) {
}
