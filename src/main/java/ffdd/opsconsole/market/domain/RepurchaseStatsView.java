package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;

public record RepurchaseStatsView(
        Long ordersMonth,
        BigDecimal principalUsd,
        BigDecimal estimatedInterestUsd) {
}
