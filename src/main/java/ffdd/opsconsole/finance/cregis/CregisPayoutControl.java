package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** One outbound capability shared by policy, operator status and the sender. Reads never send money. */
@Service
@RequiredArgsConstructor
public class CregisPayoutControl {
    private final CregisProperties config;
    private final CregisDepositMapper db;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;

    public boolean configured() {
        return config.getMode() == CregisProperties.Mode.PROVIDER && config.isPayoutEnabled()
                && config.getProjectId() > 0 && config.getDepositConfirmations() >= 15
                && StringUtils.hasText(config.getBaseUrl()) && StringUtils.hasText(config.getApiKey())
                && StringUtils.hasText(config.getCallbackBaseUrl())
                && (StringUtils.hasText(config.getBscRpcUrl()) || StringUtils.hasText(config.getEthereumRpcUrl()) || StringUtils.hasText(config.getTronRpcUrl()))
                && config.getCallbackSourceIps() != null
                && Arrays.stream(config.getCallbackSourceIps().split(",")).anyMatch(StringUtils::hasText);
    }

    /** The existing reconciliation/risk qualifications apply before reopening any real-money switch. */
    public String recoveryReason() {
        BigDecimal exposure = db.unresolvedExposure(config.getProjectId());
        if (exposure == null || exposure.compareTo(new BigDecimal("500")) >= 0
                || db.criticalHeldDepositCount(config.getProjectId()) != 0
                || db.disputedDeliveryCountForProject(config.getProjectId()) != 0
                || db.pendingAcceptedDeliveryCount(0) != 0
                || db.openCriticalAlertCountExceptManual(config.getProjectId()) != 0
                || db.openAutoForwardUnknownAlertCount(config.getProjectId()) != 0
                || !db.uncertainAddresses(config.getProjectId()).isEmpty()
                || !db.providerMissing(config.getProjectId()).isEmpty()
                || !db.failedDeliveries(config.getProjectId()).isEmpty()) return "RISK_HOLD";
        return db.recentCompleteReconcileCount(config.getProjectId()) == 1
                ? null : "RECONCILIATION_REQUIRED";
    }

    public Map<String, Object> capability() {
        String reason = "NOT_CONFIGURED";
        boolean configured = configured();
        if (configured) {
            Map<String, Object> gate = db.provisionGate();
            reason = gate == null || !"IDLE".equals(gate.get("state")) ? "RISK_HOLD"
                    : !on(gate.get("payoutEnabled")) ? "SWITCH_OFF" : recoveryReason();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured);
        result.put("enabled", reason == null);
        result.put("disabledReason", reason);
        return result;
    }

    public String recoveryReason(CregisNetwork network) {
        String reason=recoveryReason();
        return reason!=null?reason:db.recentCompleteReconcileCountForNetwork(network,config.getProjectId())==1?null:"RECONCILIATION_REQUIRED";
    }
    public boolean enabled() { return Boolean.TRUE.equals(capability().get("enabled")); }
    public boolean configured(CregisNetwork network) {
        return network != null && configured() && StringUtils.hasText(config.rpcUrl(network))
                && config.payoutNetworkEnabled(network);
    }
    /** Address support and payout support are distinct Cregis project permissions. */
    public Map<String,Object> capability(CregisNetwork network) {
        var result = new LinkedHashMap<String,Object>();
        boolean configured = configured(network);
        String reason = configured ? (String) capability().get("disabledReason") : "NOT_CONFIGURED";
        if (configured && reason == null && db.recentCompleteReconcileCountForNetwork(network, config.getProjectId()) != 1)
            reason = "RECONCILIATION_REQUIRED";
        if (configured && reason == null) {
            try {
                if (router.provider().projectCoins().stream().noneMatch(coin -> coin.payoutEnabled()
                        && network.chainId().equals(coin.chainId()) && network.tokenMatches(coin.tokenId())))
                    reason = "PAYOUT_ASSET_UNAVAILABLE";
            } catch (RuntimeException unavailable) { reason = "PROVIDER_CAPABILITY_UNAVAILABLE"; }
        }
        if(configured && reason==null && network==CregisNetwork.TRC20) {
            try { chain.head(network); }
            catch(RuntimeException unavailable) { reason="CHAIN_PROOF_UNAVAILABLE"; }
        }
        result.put("network", network.network()); result.put("configured", configured);
        result.put("enabled", reason == null); result.put("disabledReason", reason);
        return result;
    }
    public boolean enabled(CregisNetwork network) { return Boolean.TRUE.equals(capability(network).get("enabled")); }
    public java.util.List<Map<String,Object>> networkCapabilities() {
        return java.util.Arrays.stream(CregisNetwork.values()).map(this::capability).toList();
    }
    public int requiredConfirmations() { return Math.max(15, config.getDepositConfirmations()); }
    private static boolean on(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }
}
