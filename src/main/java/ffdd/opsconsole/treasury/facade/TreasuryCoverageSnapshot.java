package ffdd.opsconsole.treasury.facade;

import java.math.BigDecimal;

public record TreasuryCoverageSnapshot(
        BigDecimal coverageRatio,
        BigDecimal redlinePct,
        boolean reliable,
        BigDecimal reserveUsd,
        BigDecimal liabilitiesUsd,
        BigDecimal nexUsdRate) {

    public TreasuryCoverageSnapshot(BigDecimal coverageRatio, BigDecimal redlinePct) {
        this(coverageRatio, redlinePct, true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public TreasuryCoverageSnapshot(BigDecimal coverageRatio, BigDecimal redlinePct, boolean reliable) {
        this(coverageRatio, redlinePct, reliable, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
