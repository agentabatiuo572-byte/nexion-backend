package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NexMarketRepository {
    Optional<BigDecimal> latestNexUsdtPrice();

    Optional<String> latestNexSparkline();

    void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt);

    void ensureNexMarketSeedData(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt);

    void ensureExchangeSeedData();

    void ensureGenesisSeedData();

    Optional<GenesisSeriesView> activeGenesisSeries();

    GenesisSecondaryStatsView genesisSecondaryStats(LocalDateTime since);

    long genesisHoldingCount();

    List<GenesisNodeView> genesisNodes(int offset, int limit);

    BigDecimal todayExchangeCompletedUsdt();

    long queuedExchangeCount();

    long todayExchangeCountByStatus(String status);

    List<ExchangeOrderView> exchangeOrdersByStatuses(List<String> statuses, int limit);

    Optional<ExchangeOrderView> findExchangeOrder(String exchangeNo);

    boolean updateExchangeStatus(String exchangeNo, String status);

    boolean cancelQueuedExchange(String exchangeNo);

    void ensureStakingSeedData();

    List<StakingProductView> stakingProducts();

    BigDecimal stakingEstimatedInterestUsdt();

    long stakingPositionCountByStatus(String status);

    long stakingEarlyWithdrawnCountSince(LocalDateTime since);

    List<StakingPositionView> stakingPositionsByStatus(String status, int limit);

    void ensureRepurchaseSeedData();

    RepurchaseStatsView repurchaseStatsSince(LocalDateTime since);

    List<RepurchaseStatusView> repurchaseStatusBreakdown();

    List<RepurchaseAmountBucketView> repurchaseAmountBuckets();
}
