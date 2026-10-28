package ffdd.opsconsole.device.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** User-visible E4 refund reason; the operator's free-text note stays in the audit log only. */
public enum OrderRefundReason {
    USER_REQUEST,
    DUPLICATE_PAYMENT,
    DELIVERY_UNAVAILABLE,
    ORDER_EXCEPTION,
    OTHER;

    public static Optional<OrderRefundReason> parse(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(reason -> reason.name().equals(normalized)).findFirst();
    }
}
