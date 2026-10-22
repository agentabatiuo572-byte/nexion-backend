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

    public boolean configured() {
        return config.getMode() == CregisProperties.Mode.PROVIDER && config.isPayoutEnabled()
                && config.getProjectId() > 0 && config.getDepositConfirmations() >= 15
                && StringUtils.hasText(config.getBaseUrl()) && StringUtils.hasText(config.getApiKey())
                && StringUtils.hasText(config.getCallbackBaseUrl()) && StringUtils.hasText(config.getBscRpcUrl())
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

    public boolean enabled() { return Boolean.TRUE.equals(capability().get("enabled")); }
    public int requiredConfirmations() { return Math.max(15, config.getDepositConfirmations()); }
    private static boolean on(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }
}
