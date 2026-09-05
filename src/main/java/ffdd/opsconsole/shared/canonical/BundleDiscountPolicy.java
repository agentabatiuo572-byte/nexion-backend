package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Server-owned bundle pricing ladder shared by App checkout and E1 operations. */
public record BundleDiscountPolicy(BigDecimal twoItems, BigDecimal threeItems, BigDecimal fourPlusItems) {
    public static final String TWO_ITEMS_KEY = "store.bundle.discount.2.rate";
    public static final String THREE_ITEMS_KEY = "store.bundle.discount.3.rate";
    public static final String FOUR_PLUS_ITEMS_KEY = "store.bundle.discount.4plus.rate";
    public static final String VERSION_KEY = "store.bundle.discount.version";
    public static final List<String> RATE_KEYS = List.of(TWO_ITEMS_KEY, THREE_ITEMS_KEY, FOUR_PLUS_ITEMS_KEY);

    public BundleDiscountPolicy {
        twoItems = normalized(twoItems);
        threeItems = normalized(threeItems);
        fourPlusItems = normalized(fourPlusItems);
        if (twoItems.signum() <= 0 || twoItems.compareTo(new BigDecimal("0.50")) > 0
                || threeItems.compareTo(twoItems) < 0 || threeItems.compareTo(new BigDecimal("0.50")) > 0
                || fourPlusItems.compareTo(threeItems) < 0 || fourPlusItems.compareTo(new BigDecimal("0.50")) > 0) {
            throw new IllegalArgumentException("BUNDLE_DISCOUNT_POLICY_INVALID");
        }
    }

    public BigDecimal rateFor(int itemCount) {
        if (itemCount >= 4) return fourPlusItems;
        if (itemCount == 3) return threeItems;
        if (itemCount == 2) return twoItems;
        return BigDecimal.ZERO;
    }

    public static BundleDiscountPolicy require(Function<String, Optional<String>> reader) {
        try {
            return new BundleDiscountPolicy(
                    decimal(reader.apply(TWO_ITEMS_KEY)),
                    decimal(reader.apply(THREE_ITEMS_KEY)),
                    decimal(reader.apply(FOUR_PLUS_ITEMS_KEY)));
        } catch (RuntimeException ex) {
            throw new BizException(503, "BUNDLE_DISCOUNT_POLICY_UNAVAILABLE");
        }
    }

    /** Unit-test compatibility only; production paths require persisted rows. */
    static BundleDiscountPolicy testDefaults() {
        return new BundleDiscountPolicy(new BigDecimal("0.05"), new BigDecimal("0.08"), new BigDecimal("0.12"));
    }

    private static BigDecimal decimal(Optional<String> value) {
        String raw = value.filter(item -> !item.isBlank()).orElseThrow();
        return new BigDecimal(raw.trim());
    }

    private static BigDecimal normalized(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("BUNDLE_DISCOUNT_POLICY_INVALID");
        return value.stripTrailingZeros();
    }
}
