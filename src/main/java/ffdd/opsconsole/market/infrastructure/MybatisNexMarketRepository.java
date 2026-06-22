package ffdd.opsconsole.market.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.domain.ExchangeOrderView;
import ffdd.opsconsole.market.mapper.ExchangeOrderMapper;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisNexMarketRepository implements NexMarketRepository {
    private final NexMarketMapper mapper;
    private final ExchangeOrderMapper exchangeOrderMapper;

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
    public boolean cancelQueuedExchange(String exchangeNo) {
        return exchangeOrderMapper.cancelQueued(exchangeNo) > 0;
    }
}
