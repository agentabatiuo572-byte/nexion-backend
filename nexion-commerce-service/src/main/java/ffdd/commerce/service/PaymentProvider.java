package ffdd.commerce.service;

import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import java.util.Map;

public interface PaymentProvider {
    String code();

    PaymentSession createSession(CommerceOrder order, String paymentNo, PaymentCheckoutRequest request);

    PaymentProviderCallback parseAndVerifyCallback(Map<String, String> headers, String rawBody);

    default PaymentProviderStatus queryPayment(PaymentRecord record) {
        return PaymentProviderStatus.fromRecord(record);
    }
}
