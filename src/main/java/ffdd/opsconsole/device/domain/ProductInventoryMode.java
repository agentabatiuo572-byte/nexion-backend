package ffdd.opsconsole.device.domain;

import java.util.Locale;

/** Canonical inventory semantics persisted by nx_product.inventory_mode. */
public enum ProductInventoryMode {
    FINITE,
    UNLIMITED;

    public static ProductInventoryMode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static boolean isUnlimited(String raw) {
        return UNLIMITED == parse(raw);
    }
}
