package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;

public record GenesisPolicyView(
        Integer totalSupply,
        Integer soldSupply,
        BigDecimal priceUsdt,
        BigDecimal dailyDividendRatePct,
        BigDecimal royaltyPct,
        String dividendBaseFormula) {
}
