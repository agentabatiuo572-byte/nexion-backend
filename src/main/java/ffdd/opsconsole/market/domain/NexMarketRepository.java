package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface NexMarketRepository {
    Optional<BigDecimal> latestNexUsdtPrice();

    void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt);
}
