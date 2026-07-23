package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TopupChargebackSource(
        String paymentNo,
        String orderNo,
        Long userId,
        String provider,
        String providerPaymentId,
        BigDecimal amountUsdt,
        String status,
        LocalDateTime latestChargebackOccurredAt) {
}
