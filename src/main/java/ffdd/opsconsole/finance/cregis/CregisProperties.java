package ffdd.opsconsole.finance.cregis;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ToString
@Component
@ConfigurationProperties(prefix = "nexion.finance.cregis")
public class CregisProperties {
    public enum Mode {
        DISABLED,
        LOCAL_SANDBOX,
        PROVIDER
    }

    /** DISABLED is the safe default. LOCAL_SANDBOX never contacts Cregis or moves funds. */
    private Mode mode = Mode.DISABLED;
    private String baseUrl = "";
    /** Exact public callback origin and base path controlled by this backend. */
    private String callbackBaseUrl = "";
    private long projectId;
    /** Server-side only. Never expose through an application or admin response. */
    @ToString.Exclude
    private String apiKey = "";
    private int connectTimeoutMs = 1000;
    private int readTimeoutMs = 2000;
    /** Separate pay-in switch; payout settings do not authorize address issuance or credit. */
    private boolean depositEnabled;
    /** Pauses new wallet credits while callback, provider query and chain observation continue. */
    private boolean depositCreditEnabled;
    /** Production crypto payout is outside the accepted pay-in/bank-withdrawal scope. */
    private boolean payoutEnabled;
    /** Comma-separated, explicitly approved pilot user IDs. Empty means no users. */
    private String depositPilotUserIds = "";
    /** Read-only BSC JSON-RPC endpoint; no keys or transaction methods are used. */
    @ToString.Exclude
    private String bscRpcUrl = "";
    private int depositConfirmations = 15;
}
