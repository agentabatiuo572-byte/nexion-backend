package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;

public record GrowthEarnMilestoneUpdateRequest(
        BigDecimal thresholdUsd,
        BigDecimal rewardNex,
        String reason,
        String operator) {
}
