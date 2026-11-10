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
    private boolean trc20DepositEnabled;
    public boolean depositNetworkEnabled(CregisNetwork network) { return switch(network) { case BEP20 -> true; case ERC20 -> erc20DepositEnabled; case TRC20 -> trc20DepositEnabled; }; }
    /** Provider payout transport approval; the independent D1 maker/checker database switch is also required. */
    private boolean payoutEnabled;
    /** Explicit Ethereum outbound transport approval; existing BSC approvals never enable a new network. */
    private boolean erc20PayoutEnabled;
    private boolean trc20PayoutEnabled;
    public boolean payoutNetworkEnabled(CregisNetwork network) { return switch(network) { case BEP20 -> true; case ERC20 -> erc20PayoutEnabled; case TRC20 -> trc20PayoutEnabled; }; }
    /** Single operator approval for the automatic collection/forward transport across all networks. Never enabled by a migration. */
    private boolean autoForwardEnabled;
    private String erc20PlatformAddress = "";
    private String erc20CounterpartyAddress = "";
    private String trc20PlatformAddress = "";
    private String trc20CounterpartyAddress = "";
    public boolean autoForwardNetworkEnabled(CregisNetwork network) {
        return autoForwardEnabled;
    }
    public String autoForwardPlatform(CregisNetwork network) {
        return switch(network) { case BEP20 -> CregisFundsPreparationService.PLATFORM; case ERC20 -> erc20PlatformAddress; case TRC20 -> trc20PlatformAddress; };
    }
    public String autoForwardCounterparty(CregisNetwork network) {
        return switch(network) { case BEP20 -> CregisFundsPreparationService.COUNTERPARTY; case ERC20 -> erc20CounterpartyAddress; case TRC20 -> trc20CounterpartyAddress; };
    }
    public boolean autoForwardAddressesConfigured(CregisNetwork network) {
        return network.validAddress(autoForwardPlatform(network)) && network.validAddress(autoForwardCounterparty(network))
                && !network.addressEquals(autoForwardPlatform(network), autoForwardCounterparty(network));
    }
    /** Exact Cregis callback egress IPs; empty rejects callbacks until verified with vendor. */
    private String callbackSourceIps = "";
    /** Read-only BSC JSON-RPC endpoint; no keys or transaction methods are used. */
    @ToString.Exclude
    private String bscRpcUrl = "";
    /** Independent read-only Ethereum mainnet endpoint. No BSC fallback is permitted. */
    @ToString.Exclude
    private String ethereumRpcUrl = "";
    /** Independent TRON native HTTP API origin; wallet and walletsolidity reads only. */
    @ToString.Exclude
    private String tronRpcUrl = "";
    @ToString.Exclude
    private String tronApiKey = "";
    /** Shared native TRON request spacing, including proof reads from concurrent business paths. */
    private int tronRequestMinIntervalMs = 500;
    public int getTronRequestMinIntervalMs() {
        if (tronRequestMinIntervalMs <= 0) throw new IllegalStateException("CREGIS_TRON_REQUEST_INTERVAL_INVALID");
        return tronRequestMinIntervalMs;
    }
    private int depositConfirmations = 15;
    public int confirmations(CregisNetwork network) { return getDepositConfirmations(); }
    public String rpcUrl(CregisNetwork network) { return switch(network) { case BEP20 -> bscRpcUrl; case ERC20 -> ethereumRpcUrl; case TRC20 -> tronRpcUrl; }; }
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
