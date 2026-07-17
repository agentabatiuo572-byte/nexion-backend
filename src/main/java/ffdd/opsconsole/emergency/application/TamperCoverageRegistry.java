package ffdd.opsconsole.emergency.application;

import java.util.List;
import java.util.Map;

/**
 * Release-time truth for J3 interception coverage. A path becomes active only after a production
 * business rejection point and its integration test both call the server-only publisher.
 */
final class TamperCoverageRegistry {
    private static final List<String> REGISTERED = List.of(
            "free_trial_state", "wallet_pairing", "risk_disclosure_ack", "two_factor_state",
            "product_phase_override", "device_slot_cap", "dev_seed_state", "otp_verification",
            "bill_client_push", "client_minted_id", "charge_fail_rate");
    private static final List<String> ACTIVE = REGISTERED;

    private TamperCoverageRegistry() {
    }

    static Map<String, Object> snapshot() {
        return Map.of(
                "status", ACTIVE.size() == REGISTERED.size() ? "complete" : "partial",
                "registeredCount", REGISTERED.size(),
                "activeCount", ACTIVE.size(),
                "registeredPaths", REGISTERED,
                "activePaths", ACTIVE,
                "missingPaths", REGISTERED.stream().filter(path -> !ACTIVE.contains(path)).toList());
    }
}
