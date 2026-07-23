package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TopupCardSettlementRequest(
        String settlementEventId,
        String admissionEventId,
        String paymentNo,
        String orderNo,
        Long userId,
        String provider,
        String providerPaymentId,
        BigDecimal amountUsdt,
        BigDecimal feeAmountUsdt,
        BigDecimal feeRatePct,
        LocalDateTime occurredAt) {
}
