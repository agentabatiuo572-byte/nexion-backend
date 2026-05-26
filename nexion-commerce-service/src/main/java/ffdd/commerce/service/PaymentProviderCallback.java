package ffdd.commerce.service;

import java.math.BigDecimal;

public record PaymentProviderCallback(
        String eventId,
        String paymentNo,
        String providerPaymentId,
        String orderNo,
        String status,
        BigDecimal amountUsdt,
        String currency,
        String failureReason) {
}
