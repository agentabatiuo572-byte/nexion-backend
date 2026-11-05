package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;

final class D2WithdrawalReviewThreshold {
    private static final String KEY = "withdrawal.strong_review_threshold_usdt";
    private static final BigDecimal MIN = new BigDecimal("20");
    private static final BigDecimal MAX = new BigDecimal("10000000");
    static final String UNAVAILABLE = "A3_STRONG_REVIEW_THRESHOLD_UNAVAILABLE";

    private D2WithdrawalReviewThreshold() {}

    static BigDecimal read(PlatformConfigFacade config) {
        String raw = config.activeValue(KEY).orElse(null);
        if (raw == null) return null;
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) return null;
            return value.setScale(6, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    static BigDecimal require(PlatformConfigFacade config) {
        BigDecimal threshold = read(config);
        if (threshold == null) throw new BizException(503, UNAVAILABLE);
        return threshold;
    }

    static boolean applies(BigDecimal threshold, BigDecimal amount) {
        return amount != null && amount.compareTo(threshold) >= 0;
    }
}
