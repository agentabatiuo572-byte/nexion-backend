package ffdd.opsconsole.finance.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Trust boundary for payment-method tokenization.
 * DISABLED is the safe default. LOCAL_SANDBOX accepts only explicitly tagged local mock tokens.
 * PROVIDER remains fail-closed until a real provider verifier is installed.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nexion.finance.payment-method-provider")
public class PaymentMethodProviderProperties {
    public enum Mode { DISABLED, LOCAL_SANDBOX, PROVIDER }

    private Mode mode = Mode.DISABLED;
}
