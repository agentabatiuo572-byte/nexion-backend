package ffdd.opsconsole.finance.hdpay;

import java.math.BigDecimal;

public interface HdPayPayoutGateway {
    /** No success state is returned: code 200 only acknowledges submission. */
    void create(Request request);
    Order query(String merchantOrderId);

    record Request(String merchantOrderId, BigDecimal amount, String bankCode, String account, String holder, String clientIp) {
        @Override public String toString() { return "HdPayPayoutRequest[REDACTED]"; }
    }
    record Order(String merchantOrderId, long providerOrderId, int status, BigDecimal amount,
                 String account, String holder, String type) {
        @Override public String toString() { return "HdPayPayoutOrder[REDACTED]"; }
    }
}
