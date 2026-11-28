package ffdd.opsconsole.shared.canonical;

/** Read-side validation for the two independent sales counters used by the storefront. */
public final class StorefrontDisplaySales {
    private static final int MAX_BASE = 9_999_999;

    private StorefrontDisplaySales() { }

    public static Long total(Integer soldCount, Integer displaySoldBase) {
        if (soldCount == null || soldCount < 0
                || displaySoldBase == null || displaySoldBase < 0 || displaySoldBase > MAX_BASE) {
            return null;
        }
        return soldCount.longValue() + displaySoldBase.longValue();
    }
}
