package ffdd.opsconsole.market.dto;

import java.math.BigDecimal;

public record NexMarketCurveFrame(
        int dayIndex,
        BigDecimal targetPrice,
        BigDecimal pumpProbability,
        BigDecimal volatilityPct) {
}
