package ffdd.opsconsole.finance.cregis;

import java.math.BigDecimal;

final class CregisAmount {
    private static final int MAX_TEXT_LENGTH = 80;
    private static final int MAX_INTEGER_DIGITS = 18;
    private static final int MAX_FRACTION_DIGITS = 18;

    private CregisAmount() { }

    static BigDecimal normalizePositive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        long integerDigits = Math.max(1L, (long) normalized.precision() - normalized.scale());
        int fractionDigits = Math.max(0, normalized.scale());
        if (integerDigits > MAX_INTEGER_DIGITS || fractionDigits > MAX_FRACTION_DIGITS) return null;
        return normalized;
    }

    static BigDecimal parsePositive(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) return null;
        try {
            return normalizePositive(new BigDecimal(value));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
