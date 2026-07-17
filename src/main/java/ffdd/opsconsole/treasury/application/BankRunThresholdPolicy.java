package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;

/** One normalization policy shared by the B5 display and the J1 R1 trigger. */
public final class BankRunThresholdPolicy {
    public static final String YELLOW_CONFIG_KEY = "risk.bankrun-yellow-pct";
    public static final String REDLINE_CONFIG_KEY = "risk.bankrun-red-pct";
    public static final BigDecimal DEFAULT_YELLOW_PCT = new BigDecimal("20");
    public static final BigDecimal DEFAULT_REDLINE_PCT = new BigDecimal("40");
    public static final BigDecimal MIN_YELLOW_PCT = new BigDecimal("5");
    public static final BigDecimal MAX_YELLOW_PCT = new BigDecimal("50");
    public static final BigDecimal MIN_REDLINE_PCT = new BigDecimal("10");
    public static final BigDecimal MAX_REDLINE_PCT = new BigDecimal("80");

    private BankRunThresholdPolicy() {
    }

    public static Bands resolve(PlatformConfigFacade configFacade) {
        BigDecimal yellow = bounded(
                configFacade.activeValue(YELLOW_CONFIG_KEY).orElse(null),
                DEFAULT_YELLOW_PCT,
                MIN_YELLOW_PCT,
                MAX_YELLOW_PCT);
        BigDecimal redline = bounded(
                configFacade.activeValue(REDLINE_CONFIG_KEY).orElse(null),
                DEFAULT_REDLINE_PCT,
                MIN_REDLINE_PCT,
                MAX_REDLINE_PCT);
        if (redline.compareTo(yellow) <= 0) {
            return new Bands(DEFAULT_YELLOW_PCT, DEFAULT_REDLINE_PCT);
        }
        return new Bands(yellow.stripTrailingZeros(), redline.stripTrailingZeros());
    }

    private static BigDecimal bounded(
            String value, BigDecimal fallback, BigDecimal minInclusive, BigDecimal maxInclusive) {
        try {
            BigDecimal parsed = new BigDecimal(value == null ? "" : value.trim());
            return parsed.compareTo(minInclusive) >= 0 && parsed.compareTo(maxInclusive) <= 0
                    ? parsed
                    : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public record Bands(BigDecimal yellowPct, BigDecimal redlinePct) {
    }
}
