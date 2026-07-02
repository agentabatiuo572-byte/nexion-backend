package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record NexPricePointView(
        BigDecimal priceUsdt,
        BigDecimal deltaPercent,
        LocalDateTime sampledAt) {
}
