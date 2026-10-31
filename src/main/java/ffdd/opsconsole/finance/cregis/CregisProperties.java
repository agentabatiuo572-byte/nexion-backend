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
    /** Separate approval for new Ethereum address issuance and wallet credit. */
    private boolean erc20DepositEnabled;
    public boolean depositNetworkEnabled(CregisNetwork network) { return network==CregisNetwork.BEP20 || erc20DepositEnabled; }
    /** Provider payout transport approval; the independent D1 maker/checker database switch is also required. */
    private boolean payoutEnabled;
    /** Explicit Ethereum outbound transport approval; existing BSC approvals never enable a new network. */
    private boolean erc20PayoutEnabled;
    /** Separate operator approval for the automatic collection/forward transport. Never enabled by a migration. */
    private boolean autoForwardEnabled;
    /** Exact Cregis callback egress IPs; empty rejects callbacks until verified with vendor. */
    private String callbackSourceIps = "";
    /** Read-only BSC JSON-RPC endpoint; no keys or transaction methods are used. */
    @ToString.Exclude
    private String bscRpcUrl = "";
    /** Independent read-only Ethereum mainnet endpoint. No BSC fallback is permitted. */
    @ToString.Exclude
    private String ethereumRpcUrl = "";
    private int depositConfirmations = 15;
    public int confirmations(CregisNetwork network) { return getDepositConfirmations(); }
    public String rpcUrl(CregisNetwork network) { return network==CregisNetwork.BEP20 ? bscRpcUrl : ethereumRpcUrl; }
    public boolean networkConfigured(CregisNetwork network) { return rpcUrl(network)!=null && !rpcUrl(network).isBlank(); }
    /** Historical RPC batch includes the existing 100-block overlap. */
    private int chainScanBlocks = 500;

    public boolean allowsCallbackSourceIp(String sourceIp) {
        if (sourceIp == null || sourceIp.isBlank()
                || callbackSourceIps == null || callbackSourceIps.isBlank()) return false;
        for (String candidate : callbackSourceIps.split(","))
            if (sourceIp.equalsIgnoreCase(candidate.trim())) return true;
        return false;
    }

    public int getChainScanBlocks() {
        if (chainScanBlocks <= 100) throw new IllegalStateException("CREGIS_CHAIN_SCAN_BLOCKS_INVALID");
        return chainScanBlocks;
    }
}
