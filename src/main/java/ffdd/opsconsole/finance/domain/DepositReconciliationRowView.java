package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record DepositReconciliationRowView(
        String channel,
        Long providerCount,
        BigDecimal providerAmount,
        Long ledgerCount,
        BigDecimal ledgerAmount,
        BigDecimal diffAmount,
        String diff,
        Boolean reconciled) {
}
