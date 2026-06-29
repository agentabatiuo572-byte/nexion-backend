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
import org.springframework.util.StringUtils;

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
        if (mapper.countActiveNexUsdtPrices() == 0 || !StringUtils.hasText(mapper.latestNexSparkline())) {
            publishNexUsdtPrice(priceUsdt, deltaPercent, sparklineJson, sampledAt);
        }
    }

    @Override
    public void ensureExchangeSeedData() {
        if (exchangeOrderMapper.countOrders() == 0 || !hasExchangeOverviewRows()) {
            seedExchangeOrders();
        }
    }

    @Override
    public void ensureGenesisSeedData() {
        if (genesisMapper.countActiveSeries() == 0 || genesisMapper.countHoldings() == 0) {
            seedGenesisData();
        }
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

    private boolean hasExchangeOverviewRows() {
        return exchangeOrderMapper.countQueued() > 0
                || exchangeOrderMapper.countTodayByStatus("KYC_REQUIRED") > 0
                || exchangeOrderMapper.countTodayByStatus("USER_CAP") > 0
                || exchangeOrderMapper.countTodayByStatus("PLATFORM_CAP") > 0
                || exchangeOrderMapper.countTodayByStatus("GEO_BLOCKED") > 0;
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
        if (stakingMapper.countProductsByCodes(CANONICAL_STAKING_PRODUCT_CODES) < CANONICAL_STAKING_PRODUCT_CODES.size()) {
            seedStakingProducts();
        }
        if (stakingMapper.countPositions() == 0) {
            seedStakingPositions();
        }
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
        if (stakingMapper.countProductsByCodes(CANONICAL_REPURCHASE_PRODUCT_CODES) < CANONICAL_REPURCHASE_PRODUCT_CODES.size()) {
            seedRepurchaseProducts();
        }
        if (stakingMapper.countPositionsByProductCodes(CANONICAL_REPURCHASE_PRODUCT_CODES) == 0) {
            seedRepurchasePositions();
        }
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

    private void seedStakingProducts() {
        stakingMapper.upsertSeedProduct("USDT_30D", "USDT · 30d", "USDT", 30, new BigDecimal("1200"), new BigDecimal("500"), new BigDecimal("100"), 10, "ACTIVE");
        stakingMapper.upsertSeedProduct("USDT_90D", "USDT · 90d", "USDT", 90, new BigDecimal("3500"), new BigDecimal("1500"), new BigDecimal("500"), 20, "ACTIVE");
        stakingMapper.upsertSeedProduct("USDT_180D", "USDT · 180d", "USDT", 180, new BigDecimal("8000"), new BigDecimal("3000"), new BigDecimal("1000"), 30, "ACTIVE");
        stakingMapper.upsertSeedProduct("USDT_365D", "USDT · 365d", "USDT", 365, new BigDecimal("18000"), new BigDecimal("5000"), new BigDecimal("5000"), 40, "ACTIVE");
    }

    private void seedRepurchaseProducts() {
        stakingMapper.upsertSeedProduct("REPURCHASE_90D", "复投激励 · 90d", "USDT", 90, new BigDecimal("3500"), new BigDecimal("1500"), new BigDecimal("100"), 70, "ACTIVE");
    }

    private void seedExchangeOrders() {
        LocalDateTime now = LocalDateTime.now();
        exchangeOrderMapper.upsertSeedOrder(10001L, "DEMO-EX-NEX-USDT-1", "NEX", "USDT", new BigDecimal("120.000000"), new BigDecimal("20.520000"), new BigDecimal("0.17100000"), "COMPLETED", now.minusHours(4));
        exchangeOrderMapper.upsertSeedOrder(10001L, "DEMO-EX-QUEUE-1", "NEX", "USDT", new BigDecimal("280.000000"), new BigDecimal("47.880000"), new BigDecimal("0.17100000"), "QUEUED", now.minusHours(3));
        exchangeOrderMapper.upsertSeedOrder(10001L, "DEMO-EX-QUEUE-2", "NEX", "USDT", new BigDecimal("7200.000000"), new BigDecimal("1231.200000"), new BigDecimal("0.17100000"), "QUEUED", now.minusHours(2));
        exchangeOrderMapper.upsertSeedOrder(10001L, "DEMO-EX-KYC-1", "NEX", "USDT", new BigDecimal("660.000000"), new BigDecimal("112.860000"), new BigDecimal("0.17100000"), "KYC_REQUIRED", now.minusHours(1));
        exchangeOrderMapper.upsertSeedOrder(10001L, "DEMO-EX-USERCAP-1", "NEX", "USDT", new BigDecimal("310.000000"), new BigDecimal("53.010000"), new BigDecimal("0.17100000"), "USER_CAP", now.minusMinutes(45));
        exchangeOrderMapper.upsertSeedOrder(10001L, "DEMO-EX-PLATFORMCAP-1", "NEX", "USDT", new BigDecimal("5200.000000"), new BigDecimal("889.200000"), new BigDecimal("0.17100000"), "PLATFORM_CAP", now.minusMinutes(30));
    }

    private void seedGenesisData() {
        LocalDateTime now = LocalDateTime.now();
        String seriesCode = "GENESIS-2026";
        genesisMapper.upsertSeedSeries(
                seriesCode,
                "Genesis Node",
                1000,
                847,
                new BigDecimal("9999.000000"),
                250,
                now);
        genesisMapper.upsertSeedOrder("DEMO-GENESIS-PRIMARY-0042", "DEMO-GENESIS-PRIMARY-0042-REQ", 10042L, seriesCode, 1, new BigDecimal("9999.000000"), new BigDecimal("9999.000000"), now.minusDays(160));
        genesisMapper.upsertSeedOrder("DEMO-GENESIS-SECONDARY-0117", "DEMO-GENESIS-SECONDARY-0117-REQ", 10117L, seriesCode, 1, new BigDecimal("11200.000000"), new BigDecimal("11200.000000"), now.minusDays(29));
        genesisMapper.upsertSeedOrder("DEMO-GENESIS-PRIMARY-0233", "DEMO-GENESIS-PRIMARY-0233-REQ", 10233L, seriesCode, 1, new BigDecimal("12800.000000"), new BigDecimal("12800.000000"), now.minusDays(125));
        genesisMapper.upsertSeedOrder("DEMO-GENESIS-SECONDARY-VOL-24H", "DEMO-GENESIS-SECONDARY-VOL-24H-REQ", 10612L, seriesCode, 15, new BigDecimal("12400.000000"), new BigDecimal("186000.000000"), now.minusHours(4));
        genesisMapper.upsertSeedHolding("#0042", 10042L, "DEMO-GENESIS-PRIMARY-0042", seriesCode, new BigDecimal("9999.000000"), "ACTIVE", now.minusDays(160));
        genesisMapper.upsertSeedHolding("#0117", 10117L, "DEMO-GENESIS-SECONDARY-0117", seriesCode, new BigDecimal("11200.000000"), "ACTIVE", now.minusDays(29));
        genesisMapper.upsertSeedHolding("#0233", 10233L, "DEMO-GENESIS-PRIMARY-0233", seriesCode, new BigDecimal("12800.000000"), "LISTED", now.minusDays(125));
    }

    private void seedStakingPositions() {
        LocalDateTime now = LocalDateTime.now();
        stakingMapper.upsertSeedPosition(19081L, "POS-9081", "USDT_90D", new BigDecimal("500"), new BigDecimal("0"), "PENDING_LOCK", now.minusDays(1), now.plusDays(89), null, null);
        stakingMapper.upsertSeedPosition(17102L, "POS-7102", "USDT_180D", new BigDecimal("1000"), new BigDecimal("197.26"), "ACTIVE", now.minusDays(90), now.plusDays(90), null, null);
        stakingMapper.upsertSeedPosition(16339L, "POS-6339", "USDT_365D", new BigDecimal("5000"), new BigDecimal("295.89"), "ACTIVE", now.minusDays(120), now.plusDays(245), null, null);
        stakingMapper.upsertSeedPosition(15412L, "POS-5412", "USDT_30D", new BigDecimal("100"), new BigDecimal("0.99"), "MATURE_UNCLAIMED", now.minusDays(40), now.minusDays(10), null, null);
        stakingMapper.upsertSeedPosition(15520L, "POS-5520", "USDT_90D", new BigDecimal("500"), new BigDecimal("43.15"), "MATURE_UNCLAIMED", now.minusDays(100), now.minusDays(10), null, null);
        stakingMapper.upsertSeedPosition(15019L, "POS-5019", "USDT_365D", new BigDecimal("5000"), new BigDecimal("0"), "EARLY_WITHDRAWN", now.minusDays(30), now.plusDays(335), null, now.minusDays(2));
    }

    private void seedRepurchasePositions() {
        LocalDateTime now = LocalDateTime.now();
        stakingMapper.upsertSeedPosition(27001L, "RPI-1001", "REPURCHASE_90D", new BigDecimal("100"), repurchaseInterest("100"), "PENDING_LOCK", now.minusDays(1), now.plusDays(89), null, null);
        stakingMapper.upsertSeedPosition(27002L, "RPI-1002", "REPURCHASE_90D", new BigDecimal("200"), repurchaseInterest("200"), "ACTIVE", now.minusDays(7), now.plusDays(83), null, null);
        stakingMapper.upsertSeedPosition(27003L, "RPI-1003", "REPURCHASE_90D", new BigDecimal("500"), repurchaseInterest("500"), "ACTIVE", now.minusDays(12), now.plusDays(78), null, null);
        stakingMapper.upsertSeedPosition(27004L, "RPI-1004", "REPURCHASE_90D", new BigDecimal("1000"), repurchaseInterest("1000"), "MATURE_UNCLAIMED", now.minusDays(95), now.minusDays(5), null, null);
        stakingMapper.upsertSeedPosition(27005L, "RPI-1005", "REPURCHASE_90D", new BigDecimal("5000"), repurchaseInterest("5000"), "MATURE_UNCLAIMED", now.minusDays(99), now.minusDays(9), null, null);
        stakingMapper.upsertSeedPosition(27006L, "RPI-1006", "REPURCHASE_90D", new BigDecimal("10000"), BigDecimal.ZERO, "EARLY_WITHDRAWN", now.minusDays(32), now.plusDays(58), null, now.minusDays(1));
    }

    private BigDecimal repurchaseInterest(String amount) {
        return new BigDecimal(amount)
                .multiply(new BigDecimal("35"))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(90))
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
    }
}
