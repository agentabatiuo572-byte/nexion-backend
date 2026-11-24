package ffdd.opsconsole.finance.application;

import java.math.BigDecimal;

final class WithdrawalMinimumAmount {
    static final String CONFIG_KEY = "withdrawal.min_usdt";
    private static final BigDecimal MAX = new BigDecimal("999999999999");

    private WithdrawalMinimumAmount() {}

    static boolean valid(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(MAX) <= 0
                && value.stripTrailingZeros().scale() <= 0;
    }

    static BigDecimal fromConfig(String value) {
        if (value == null) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            return valid(parsed) ? parsed.setScale(0) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
