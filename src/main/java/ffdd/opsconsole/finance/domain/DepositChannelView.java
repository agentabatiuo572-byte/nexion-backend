package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record DepositChannelView(
        String id,
        String code,
        String fee,
        String minAmount,
        Boolean enabled,
        BigDecimal feeValue,
        String feeUnit,
        BigDecimal minAmountValue,
        String minAmountUnit) {
}
