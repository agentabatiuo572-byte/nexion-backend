package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;

public record RepurchaseAmountBucketView(
        BigDecimal amountUsdt,
        Long orderCount,
        BigDecimal principalUsd) {
}
