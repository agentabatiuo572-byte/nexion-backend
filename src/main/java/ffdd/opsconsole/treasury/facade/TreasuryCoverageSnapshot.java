package ffdd.opsconsole.treasury.facade;

import java.math.BigDecimal;

public record TreasuryCoverageSnapshot(
        BigDecimal coverageRatio,
        BigDecimal redlinePct) {
}
