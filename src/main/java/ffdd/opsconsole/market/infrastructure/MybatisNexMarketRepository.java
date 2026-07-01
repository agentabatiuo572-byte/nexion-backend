package ffdd.opsconsole.market.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.domain.ExchangeOrderView;
import ffdd.opsconsole.market.domain.GenesisNodeView;
import ffdd.opsconsole.market.domain.GenesisSecondaryStatsView;
import ffdd.opsconsole.market.domain.GenesisSeriesView;
import ffdd.opsconsole.market.domain.RepurchaseAmountBucketView;
import ffdd.opsconsole.market.domain.RepurchaseStatsView;
import ffdd.opsconsole.market.domain.RepurchaseStatusView;
import ffdd.opsconsole.market.domain.StakingPositionView;
import ffdd.opsconsole.market.domain.StakingProductView;
import ffdd.opsconsole.market.mapper.ExchangeOrderMapper;
import ffdd.opsconsole.market.mapper.GenesisMapper;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import ffdd.opsconsole.market.mapper.StakingMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisNexMarketRepository implements NexMarketRepository {
    private static final List<String> CANONICAL_STAKING_PRODUCT_CODES = List.of(
            "USDT_30D",
            "USDT_90D",
            "USDT_180D",
            "USDT_365D");
    private static final List<String> CANONICAL_REPURCHASE_PRODUCT_CODES = List.of("REPURCHASE_90D");

    private final NexMarketMapper mapper;
    private final ExchangeOrderMapper exchangeOrderMapper;
    private final StakingMapper stakingMapper;
    private final GenesisMapper genesisMapper;

    @Override
    public Optional<BigDecimal> latestNexUsdtPrice() {
        return Optional.ofNullable(mapper.latestNexUsdtPrice());
    }

    @Override
    public Optional<String> latestNexSparkline() {
        return Optional.ofNullable(mapper.latestNexSparkline());
    }

    @Override
    public void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt) {
        mapper.insertNexUsdtPrice(
                priceUsdt.setScale(8, RoundingMode.HALF_UP),
                deltaPercent.setScale(4, RoundingMode.HALF_UP),
                sparklineJson,
                sampledAt);
    }

    @Override
    public void ensureNexMarketSeedData(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt) {
        // Market price rows are business data and must be produced by explicit G3 writes.
    }

    @Override
    public void ensureExchangeSeedData() {
        // Exchange orders are business data and must not be fabricated on reads.
    }

    @Override
    public void ensureGenesisSeedData() {
        // Genesis series, orders and holdings are business data and must be written by G4 flows.
    }

    @Override
    public Optional<GenesisSeriesView> activeGenesisSeries() {
        return Optional.ofNullable(genesisMapper.activeSeries());
    }

    @Override
    public GenesisSecondaryStatsView genesisSecondaryStats(LocalDateTime since) {
        GenesisSecondaryStatsView value = genesisMapper.secondaryStats(since);
        if (value == null) {
            return new GenesisSecondaryStatsView(BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L);
        }
        return value;
    }

    @Override
    public long genesisHoldingCount() {
        return genesisMapper.countHoldings();
    }

    @Override
    public List<GenesisNodeView> genesisNodes(int offset, int limit) {
        return genesisMapper.listNodes(offset, limit);
    }

    @Override
    public BigDecimal todayExchangeCompletedUsdt() {
        BigDecimal value = exchangeOrderMapper.todayCompletedUsdt();
        return value == null ? BigDecimal.ZERO : value;
    }

    @Override
    public long queuedExchangeCount() {
        return exchangeOrderMapper.countQueued();
    }

    @Override
    public long todayExchangeCountByStatus(String status) {
        return exchangeOrderMapper.countTodayByStatus(status);
    }

    @Override
    public List<ExchangeOrderView> exchangeOrdersByStatuses(List<String> statuses, int limit) {
        return exchangeOrderMapper.listByStatuses(statuses, limit);
    }

    @Override
    public Optional<ExchangeOrderView> findExchangeOrder(String exchangeNo) {
        return Optional.ofNullable(exchangeOrderMapper.findByExchangeNo(exchangeNo));
    }

    @Override
    public boolean updateExchangeStatus(String exchangeNo, String status) {
        return exchangeOrderMapper.updateStatus(exchangeNo, status) > 0;
    }

    @Override
    public boolean updateExchangeStatusIfCurrent(String exchangeNo, String status, List<String> currentStatuses) {
        return exchangeOrderMapper.updateStatusIfCurrent(exchangeNo, status, currentStatuses) > 0;
    }

    @Override
    public boolean cancelQueuedExchange(String exchangeNo) {
        return exchangeOrderMapper.cancelQueued(exchangeNo) > 0;
    }

    @Override
    public void ensureStakingSeedData() {
        // Staking products and positions must come from G1 business/config writes.
    }

    @Override
    public List<StakingProductView> stakingProducts() {
        return stakingMapper.listProductsWithMetrics(CANONICAL_STAKING_PRODUCT_CODES);
    }

    @Override
    public BigDecimal stakingEstimatedInterestUsdt() {
        BigDecimal value = stakingMapper.sumEstimatedInterestUsdt();
        return value == null ? BigDecimal.ZERO : value;
    }

    @Override
    public long stakingPositionCountByStatus(String status) {
        return stakingMapper.countPositionsByStatus(status);
    }

    @Override
    public long stakingEarlyWithdrawnCountSince(LocalDateTime since) {
        return stakingMapper.countEarlyWithdrawnSince(since);
    }

    @Override
    public List<StakingPositionView> stakingPositionsByStatus(String status, int limit) {
        return stakingMapper.listPositionsByStatus(status, limit);
    }

    @Override
    public void ensureRepurchaseSeedData() {
        // Repurchase products and positions must come from G7 business/config writes.
    }

    @Override
    public RepurchaseStatsView repurchaseStatsSince(LocalDateTime since) {
        RepurchaseStatsView value = stakingMapper.repurchaseStatsSince(since, CANONICAL_REPURCHASE_PRODUCT_CODES);
        if (value == null) {
            return new RepurchaseStatsView(0L, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return value;
    }

    @Override
    public List<RepurchaseStatusView> repurchaseStatusBreakdown() {
        return stakingMapper.repurchaseStatusBreakdown(CANONICAL_REPURCHASE_PRODUCT_CODES);
    }

    @Override
    public List<RepurchaseAmountBucketView> repurchaseAmountBuckets() {
        return stakingMapper.repurchaseAmountBuckets(CANONICAL_REPURCHASE_PRODUCT_CODES);
    }

}
