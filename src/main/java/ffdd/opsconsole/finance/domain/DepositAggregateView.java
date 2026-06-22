package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record DepositAggregateView(
        String channel,
        Long providerCount,
        BigDecimal providerAmount,
        Long ledgerCount,
        BigDecimal ledgerAmount) {
}
