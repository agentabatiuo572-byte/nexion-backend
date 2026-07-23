package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record TopupSettlementReceipt(
        String settlementEventId,
        String requestHash,
        String paymentNo,
        String status,
        BigDecimal walletBalanceAfter,
        BigDecimal cumulativeDepositAfter,
        BigDecimal feeBufferBalanceAfter) {
}
