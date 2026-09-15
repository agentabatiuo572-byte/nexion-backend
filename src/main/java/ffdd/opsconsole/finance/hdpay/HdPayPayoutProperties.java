package ffdd.opsconsole.finance.hdpay;

import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Separate opt-in from HDPay pay-in. BANKQR routes without a merchant bank-code allowlist. */
@Getter @Setter @Component
@ConfigurationProperties(prefix = "nexion.finance.hdpay-payout")
public class HdPayPayoutProperties {
    public static final String CALLBACK_PATH = "/openapi/v1/payments/hdpay/payout/callback";
    private boolean enabled;
    private String clientIp = "";
    /** Legacy configuration retained for compatibility; BANKQR always sends bnkCode="". */
    private Set<String> bankCodes = Set.of();

    public boolean configured(HdPayProperties transport) {
        return transport.connectionReady() && clientIp != null
                && clientIp.matches("[0-9A-Fa-f:.]{3,64}");
    }

    public boolean ready(HdPayProperties transport) { return enabled && configured(transport); }

    public String callbackUrl(HdPayProperties transport) {
        return transport.getCallbackBaseUrl().replaceAll("/+$", "") + CALLBACK_PATH;
    }
}
