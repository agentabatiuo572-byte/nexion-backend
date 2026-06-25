package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;

public record GenesisSeriesView(
        Long id,
        String seriesCode,
        String name,
        Integer totalSupply,
        Integer soldSupply,
        BigDecimal priceUsdt,
        Integer royaltyBps,
        String status) {
}
