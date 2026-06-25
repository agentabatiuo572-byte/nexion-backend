package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;

public record GenesisSecondaryStatsView(
        BigDecimal floorUsdt,
        BigDecimal volume24hUsdt,
        Long listedCount,
        Long ownerCount) {
}
