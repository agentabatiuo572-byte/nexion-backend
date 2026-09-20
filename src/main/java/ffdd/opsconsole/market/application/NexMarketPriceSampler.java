package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.domain.NexMarketRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * G3 行情历史采样器。
 *
 * <p><b>为什么需要它(zentao #66)。</b> {@code nx_price_index} 此前只在两种情况下写入:
 * 日帧推进({@link G3ScheduledAdvanceService},按日 cron)与运营手工改价。于是
 * 「近 24 小时」这个窗口里最多只有 1 个采样点,而 {@code curveHistory} 要求连续点集才能
 * 画线 —— 页面因此长期显示「近 24h 历史价格点未返回,不生成前端走势线」。
 * 这不是数据缺口,是**缺少周期生产者**:采样频率必须比查询窗口密,曲线才有意义。
 *
 * <p>本采样器只记录**已经生效的权威现价**,不产生新价格、不插值、不推测:
 * 每次 tick 读一次 {@link NexMarketRepository#latestNexUsdtPrice()},值不变时仍然落点
 * (曲线需要「价格在一段时间内保持多少」这一事实)。引擎暂停时不采样 —— 暂停期间没有
 * 权威价格变动,写重复点会伪装成「市场仍在运行」。
 *
 * <p>与手工改价/日帧推进并存:它们照旧各自写点,采样器补的是两者之间的空隙。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NexMarketPriceSampler {

    private final OpsNexMarketService marketService;
    private final NexMarketRepository marketRepository;
    private final Clock clock;

    @Scheduled(cron = "${nexion.market.nex.sample.cron:0 */5 * * * *}")
    public void sample() {
        if (marketService.isEnginePaused()) {
            return;
        }
        Optional<BigDecimal> price = marketRepository.latestNexUsdtPrice();
        if (price.isEmpty() || price.get().signum() <= 0) {
            // 还没有权威现价 —— 采样器不得凭空造一个。
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        marketRepository.publishNexUsdtPrice(
                price.get(),
                lastDeltaPercent(),
                marketRepository.latestNexSparkline().orElse("[]"),
                now);
    }

    /**
     * 采样点与上一点的涨跌幅。取不到上一点时记 0 —— 曲线的第一个点没有「相对变化」,
     * 这与 {@code curveHistory} 对首点的处理一致。
     */
    private BigDecimal lastDeltaPercent() {
        return marketService.latestPriceDeltaPercent();
    }
}
