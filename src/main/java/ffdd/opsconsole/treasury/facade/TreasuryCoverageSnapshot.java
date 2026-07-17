package ffdd.opsconsole.treasury.facade;

import java.math.BigDecimal;

public record TreasuryCoverageSnapshot(
        BigDecimal coverageRatio,
        BigDecimal redlinePct,
        boolean reliable) {

    public TreasuryCoverageSnapshot(BigDecimal coverageRatio, BigDecimal redlinePct) {
        this(coverageRatio, redlinePct, true);
    }
}
