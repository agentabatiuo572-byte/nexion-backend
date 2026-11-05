package ffdd.opsconsole.treasury.facade;

import java.math.BigDecimal;

public record TreasuryCoverageSnapshot(
        BigDecimal coverageRatio,
        BigDecimal redlinePct,
        boolean reliable,
        BigDecimal reserveUsd,
        BigDecimal liabilitiesUsd,
        BigDecimal nexUsdRate,
        BigDecimal ratioReserveUsd,
        BigDecimal ratioLiabilitiesUsd,
        BigDecimal healthyPct) {

    public TreasuryCoverageSnapshot(
            BigDecimal coverageRatio,
            BigDecimal redlinePct,
            boolean reliable,
            BigDecimal reserveUsd,
            BigDecimal liabilitiesUsd,
            BigDecimal nexUsdRate,
            BigDecimal ratioReserveUsd,
            BigDecimal ratioLiabilitiesUsd) {
        this(coverageRatio, redlinePct, reliable, reserveUsd, liabilitiesUsd, nexUsdRate,
                ratioReserveUsd, ratioLiabilitiesUsd, null);
    }

    public TreasuryCoverageSnapshot(
            BigDecimal coverageRatio,
            BigDecimal redlinePct,
            boolean reliable,
            BigDecimal reserveUsd,
            BigDecimal liabilitiesUsd,
            BigDecimal nexUsdRate) {
        this(coverageRatio, redlinePct, reliable, reserveUsd, liabilitiesUsd, nexUsdRate, reserveUsd, liabilitiesUsd);
    }

    public TreasuryCoverageSnapshot(BigDecimal coverageRatio, BigDecimal redlinePct) {
        this(coverageRatio, redlinePct, true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public TreasuryCoverageSnapshot(BigDecimal coverageRatio, BigDecimal redlinePct, boolean reliable) {
        this(coverageRatio, redlinePct, reliable, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
