package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record TopupCardSettlementResult(
        String settlementEventId,
        String paymentNo,
        String status,
        BigDecimal walletBalanceAfter,
        BigDecimal cumulativeDepositAfter,
        BigDecimal feeBufferBalanceAfter,
        boolean replay) {
}
