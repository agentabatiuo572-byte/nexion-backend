package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NexMarketRepository {
    Optional<BigDecimal> latestNexUsdtPrice();

    Optional<String> latestNexSparkline();

    void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt);

    BigDecimal todayExchangeCompletedUsdt();

    long queuedExchangeCount();

    long todayExchangeCountByStatus(String status);

    List<ExchangeOrderView> exchangeOrdersByStatuses(List<String> statuses, int limit);

    Optional<ExchangeOrderView> findExchangeOrder(String exchangeNo);

    boolean cancelQueuedExchange(String exchangeNo);
}
