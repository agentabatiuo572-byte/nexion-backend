package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;

public record GrowthEarnMilestoneCreateRequest(
        String milestoneKey,
        String label,
        BigDecimal thresholdUsd,
        BigDecimal rewardNex,
        BigDecimal sortOrder,
        String reason) {}
