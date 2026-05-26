package ffdd.commerce.service;

import ffdd.commerce.domain.PaymentRecord;
import java.math.BigDecimal;

public record PaymentProviderStatus(
        String providerPaymentId,
        String status,
        BigDecimal amountUsdt,
        String currency,
        String providerEventId,
        String failureReason) {

    public static PaymentProviderStatus fromRecord(PaymentRecord record) {
        return new PaymentProviderStatus(
                record.getProviderPaymentId(),
                record.getPaymentStatus(),
                record.getAmountUsdt(),
                record.getCurrency(),
                record.getCallbackEventId(),
                record.getFailureReason());
    }
}
