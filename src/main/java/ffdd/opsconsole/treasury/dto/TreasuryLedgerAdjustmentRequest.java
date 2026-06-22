package ffdd.opsconsole.treasury.dto;

import java.math.BigDecimal;

public record TreasuryLedgerAdjustmentRequest(
        Long userId,
        String asset,
        String direction,
        BigDecimal amount,
        String relatedBizNo,
        String reason,
        String operator) {
}
