package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;

public record RepurchaseStatusView(
        String status,
        Long orderCount,
        BigDecimal principalUsd) {
}
