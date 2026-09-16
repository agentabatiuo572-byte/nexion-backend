package ffdd.opsconsole.finance.hdpay;

import org.springframework.stereotype.Component;

/** Payout uses the same HDPay configuration as pay-in; there are no payout-specific settings. */
@Component
public class HdPayPayoutProperties {
    public static final String CALLBACK_PATH = "/openapi/v1/payments/hdpay/payout/callback";
    private org.springframework.core.env.Environment environment;

    @org.springframework.beans.factory.annotation.Autowired
    void captureEnvironment(org.springframework.core.env.Environment environment) { this.environment = environment; }

    public boolean ready(HdPayProperties transport) {
        // The isolated public-test deployment only has collection approval. This is a profile
        // boundary, not a second operational switch or a replacement credential bundle.
        return (environment == null || (!environment.getProperty("nexion.deployment.public-test", Boolean.class, false)
                && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("public-test"))))
                && transport.ready();
    }

    public String callbackUrl(HdPayProperties transport) {
        return transport.getCallbackBaseUrl().replaceAll("/+$", "") + CALLBACK_PATH;
    }
}
