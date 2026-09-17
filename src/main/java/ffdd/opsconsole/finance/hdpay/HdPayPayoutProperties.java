package ffdd.opsconsole.finance.hdpay;

import org.springframework.stereotype.Component;

/** Payout shares HDPay credentials and transport; the server supplies its verified egress IP. */
@Component
public class HdPayPayoutProperties {
    public static final String PAY_TYPE = "BANK";
    public static final String CALLBACK_PATH = "/openapi/v1/payments/hdpay/payout/callback";
    public boolean ready(HdPayProperties transport) {
        if (!transport.ready()) return false;
        try { serverIp(transport); return true; }
        catch (HdPayGatewayException invalid) { return false; }
    }

    public String serverIp(HdPayProperties transport) {
        return HdPayServerIp.requirePublicIpv4(transport.getServerIp());
    }

    public String callbackUrl(HdPayProperties transport) {
        return transport.getCallbackBaseUrl().replaceAll("/+$", "") + CALLBACK_PATH;
    }
}
