package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NexMarketRepository {
    Optional<BigDecimal> latestNexUsdtPrice();

    Optional<String> latestNexSparkline();

    List<NexPricePointView> latestNexPricePoints(int limit);

    void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt);

    void ensureNexMarketSeedData(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt);

    void ensureExchangeSeedData();

    void ensureGenesisSeedData();

    Optional<GenesisSeriesView> activeGenesisSeries();

    Optional<GenesisPolicyView> activeGenesisPolicy();

    boolean updateGenesisTotalSupply(int totalSupply);

    boolean updateGenesisPrice(BigDecimal priceUsdt);

    boolean updateGenesisDailyDividendRate(BigDecimal dailyDividendRatePct);

    boolean updateGenesisRoyalty(BigDecimal royaltyPct);

    boolean updateGenesisDividendBaseFormula(String formula);

    GenesisSecondaryStatsView genesisSecondaryStats(LocalDateTime since);

    BigDecimal genesisAccrualUsd();

    Optional<String> latestGenesisDividendRerunBatchNo();

    boolean genesisDividendBatchRerunExists(String batchNo);

    long genesisHoldingCount();

    List<GenesisNodeView> genesisNodes(int offset, int limit);

    BigDecimal todayExchangeCompletedUsdt();

    long queuedExchangeCount();

    long todayExchangeCountByStatus(String status);

    List<ExchangeOrderView> exchangeOrdersByStatuses(List<String> statuses, int limit);

    Optional<ExchangeOrderView> findExchangeOrder(String exchangeNo);

    boolean updateExchangeStatus(String exchangeNo, String status);

    boolean updateExchangeStatusIfCurrent(String exchangeNo, String status, List<String> currentStatuses);

    boolean cancelQueuedExchange(String exchangeNo);

    void ensureStakingSeedData();

    List<StakingProductView> stakingProducts();

    BigDecimal stakingEstimatedInterestUsdt();

    long stakingPositionCountByStatus(String status);

    long stakingEarlyWithdrawnCountSince(LocalDateTime since);

    List<StakingPositionView> stakingPositionsByStatus(String status, int limit);

    void ensureRepurchaseSeedData();

    Optional<StakingProductView> repurchaseProduct();

    boolean updateRepurchaseApy(BigDecimal apyPct);

    boolean updateRepurchasePenalty(BigDecimal penaltyPct);

    boolean updateRepurchaseRewardMultiplier(BigDecimal multiplier);

    boolean updateRepurchaseTicketPerOrder(int ticketPerOrder);

    boolean updateRepurchasePresetAmounts(String presetAmounts);

    RepurchaseStatsView repurchaseStatsSince(LocalDateTime since);

    List<RepurchaseStatusView> repurchaseStatusBreakdown();

    List<RepurchaseAmountBucketView> repurchaseAmountBuckets();
}
