package ffdd.opsconsole.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class VietnamPaymentPolicy {
    private VietnamPaymentPolicy() {
    }

    /**
     * Derives, but never persists, the VND quote. The spread is converted to basis points
     * before multiplication so .5-to-ten boundaries cannot drift through binary floating point.
     */
    public static BigDecimal quoteRate(BigDecimal baseRateVndPerUsdt, BigDecimal buySpreadPct) {
        BigDecimal basisPoints = buySpreadPct.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        BigDecimal raw = baseRateVndPerUsdt
                .multiply(BigDecimal.valueOf(10_000).add(basisPoints))
                .divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP);
        return raw.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN);
    }
}
