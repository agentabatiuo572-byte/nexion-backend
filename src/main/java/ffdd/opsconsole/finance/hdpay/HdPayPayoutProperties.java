package ffdd.opsconsole.finance.hdpay;

import org.springframework.stereotype.Component;

/** Payout uses the same HDPay configuration as pay-in; there are no payout-specific settings. */
@Component
public class HdPayPayoutProperties {
    public static final String CALLBACK_PATH = "/openapi/v1/payments/hdpay/payout/callback";
    public boolean ready(HdPayProperties transport) {
        return transport.ready();
    }

    public String callbackUrl(HdPayProperties transport) {
        return transport.getCallbackBaseUrl().replaceAll("/+$", "") + CALLBACK_PATH;
    }
}
