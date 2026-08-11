package ffdd.opsconsole.finance.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "nexion.finance.payout-vnd-provider")
public class PayoutVndProviderProperties {
    public enum Mode { DISABLED, LOCAL_SANDBOX, PROVIDER }

    private Mode mode = Mode.DISABLED;
    private String sandboxCallbackSecret;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode == null ? Mode.DISABLED : mode; }
    public String getSandboxCallbackSecret() { return sandboxCallbackSecret; }
    public void setSandboxCallbackSecret(String value) { sandboxCallbackSecret = value; }
}
