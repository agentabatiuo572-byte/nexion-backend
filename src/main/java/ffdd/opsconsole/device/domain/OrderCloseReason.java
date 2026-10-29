package ffdd.opsconsole.device.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** User-visible reason an order closed without completing; the operator's free-text note stays in the audit log only. */
public enum OrderCloseReason {
    USER_CANCELLED,
    PAYMENT_TIMEOUT,
    PAYMENT_NOT_RECEIVED,
    PAYMENT_AMOUNT_MISMATCH,
    PAYMENT_DISPUTED,
    DELIVERY_UNAVAILABLE,
    ORDER_EXCEPTION,
    OTHER;

    // USER_CANCELLED is written only by the App's own cancel, never chosen by an operator.
    private static final Map<String, Set<OrderCloseReason>> OPERATOR_CHOICES = Map.of(
            "cancelled", EnumSet.of(ORDER_EXCEPTION, DELIVERY_UNAVAILABLE, OTHER),
            "expired", EnumSet.of(PAYMENT_TIMEOUT),
            "payment_failed", EnumSet.of(PAYMENT_NOT_RECEIVED, PAYMENT_AMOUNT_MISMATCH, ORDER_EXCEPTION, OTHER),
            "provisioning_failed", EnumSet.of(DELIVERY_UNAVAILABLE, ORDER_EXCEPTION, OTHER),
            "chargeback", EnumSet.of(PAYMENT_DISPUTED));

    public static Optional<OrderCloseReason> parse(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(reason -> reason.name().equals(normalized)).findFirst();
    }

    public static boolean operatorMayChoose(String state, OrderCloseReason reason) {
        return OPERATOR_CHOICES.getOrDefault(state, Set.of()).contains(reason);
    }

    /** States with a single possible reason record it without asking the operator. */
    public static Optional<OrderCloseReason> implied(String state) {
        Set<OrderCloseReason> choices = OPERATOR_CHOICES.getOrDefault(state, Set.of());
        return choices.size() == 1 ? choices.stream().findFirst() : Optional.empty();
    }
}
