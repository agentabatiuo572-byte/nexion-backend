package ffdd.opsconsole.finance.hdpay;

import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Separate opt-in from HDPay pay-in. Empty bank allowlist intentionally fails closed. */
@Getter @Setter @Component
@ConfigurationProperties(prefix = "nexion.finance.hdpay-payout")
public class HdPayPayoutProperties {
    public static final String CALLBACK_PATH = "/openapi/v1/payments/hdpay/payout/callback";
    private boolean enabled;
    private String clientIp = "";
    private Set<String> bankCodes = Set.of();

    public boolean configured(HdPayProperties transport) {
        return transport.connectionReady() && clientIp != null
                && clientIp.matches("[0-9A-Fa-f:.]{3,64}") && bankCodes != null && !bankCodes.isEmpty()
                && bankCodes.size() <= 100 && bankCodes.stream().allMatch(code -> code.matches("[A-Za-z0-9]{2,16}"));
    }

    public boolean ready(HdPayProperties transport) { return enabled && configured(transport); }

    public String callbackUrl(HdPayProperties transport) {
        return transport.getCallbackBaseUrl().replaceAll("/+$", "") + CALLBACK_PATH;
    }
}
