package ffdd.opsconsole.finance.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-canonical D2 withdrawal lifecycle, including the timed hold sub-state. */
public final class D2WithdrawalStateMachine {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String REVIEW_PENDING = "REVIEW_PENDING";
    public static final String EXTENDED_HOLD = "EXTENDED_HOLD";
    public static final String REVIEW_PASSED = "REVIEW_PASSED";
    public static final String PROCESSING = "PROCESSING";
    public static final String SENT = "SENT";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REVIEW_REJECTED = "REVIEW_REJECTED";
    public static final String ADDRESS_INVALID = "ADDRESS_INVALID";
    public static final String TX_FAILED = "TX_FAILED";
    public static final String TX_ORPHANED = "TX_ORPHANED";
    public static final String REFUNDED = "REFUNDED";
    public static final String FROZEN = "FROZEN";

    private static final Map<String, String> LEGACY_ALIASES = Map.ofEntries(
            Map.entry("PENDING", SUBMITTED),
            Map.entry("REVIEWING", REVIEW_PENDING),
            Map.entry("DELAYED", EXTENDED_HOLD),
            Map.entry("PENDING_CHAIN", REVIEW_PASSED),
            Map.entry("CHAIN_SUBMITTED", SENT),
            Map.entry("SUCCESS", CONFIRMED),
            Map.entry("REJECTED", REVIEW_REJECTED),
            Map.entry("FAILED", TX_FAILED),
            Map.entry("DEAD", TX_ORPHANED));

    private static final Set<String> REFUNDABLE = Set.of(
            REVIEW_REJECTED, ADDRESS_INVALID, TX_FAILED, TX_ORPHANED);

    private D2WithdrawalStateMachine() {
    }

    public static String canonical(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return LEGACY_ALIASES.getOrDefault(value, value);
    }

    public static String next(String status, String action) {
        String current = canonical(status);
        String command = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        return switch (command) {
            case "APPROVE" -> REVIEW_PENDING.equals(current) ? REVIEW_PASSED : null;
            case "DELAY" -> REVIEW_PENDING.equals(current) ? EXTENDED_HOLD : null;
            case "FREEZE" -> Set.of(REVIEW_PENDING, REVIEW_PASSED, PROCESSING).contains(current)
                    ? FROZEN : null;
            case "UNFREEZE" -> FROZEN.equals(current) ? REVIEW_PENDING : null;
            case "REJECT" -> REVIEW_PENDING.equals(current) ? REVIEW_REJECTED : null;
            case "REFUND" -> REFUNDABLE.contains(current) ? REFUNDED : null;
            default -> null;
        };
    }

    public static boolean isRefundable(String status) {
        return REFUNDABLE.contains(canonical(status));
    }
}
