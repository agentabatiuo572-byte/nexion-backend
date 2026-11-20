package ffdd.opsconsole.treasury.facade;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        BigDecimal healthyPct,
        @JsonIgnore boolean redlineBreached) {

    public TreasuryCoverageSnapshot(
            BigDecimal coverageRatio,
            BigDecimal redlinePct,
            boolean reliable,
            BigDecimal reserveUsd,
            BigDecimal liabilitiesUsd,
            BigDecimal nexUsdRate,
            BigDecimal ratioReserveUsd,
            BigDecimal ratioLiabilitiesUsd,
            BigDecimal healthyPct) {
        this(coverageRatio, redlinePct, reliable, reserveUsd, liabilitiesUsd, nexUsdRate,
                ratioReserveUsd, ratioLiabilitiesUsd, healthyPct,
                coverageRatio != null && redlinePct != null && coverageRatio.compareTo(redlinePct) < 0);
    }

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
