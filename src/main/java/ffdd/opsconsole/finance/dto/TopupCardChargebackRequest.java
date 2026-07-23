package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TopupCardChargebackRequest(
        String chargebackEventId,
        String paymentNo,
        String orderNo,
        Long userId,
        String provider,
        String providerPaymentId,
        BigDecimal amountUsdt,
        String chargebackStatus,
        String chargebackReason,
        String evidenceRef,
        LocalDateTime occurredAt) {
}
