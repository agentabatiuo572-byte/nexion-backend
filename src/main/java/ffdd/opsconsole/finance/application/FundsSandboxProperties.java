package ffdd.opsconsole.finance.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit trust boundary for the durable, user-isolated fake-funds ledger. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nexion.finance.funds-sandbox")
public class FundsSandboxProperties {
    public enum Mode { DISABLED, LOCAL_SANDBOX, PROVIDER }

    /** Safe default. PROVIDER also fails closed until a real rail is installed. */
    private Mode mode = Mode.DISABLED;
}
