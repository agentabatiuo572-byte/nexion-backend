package ffdd.opsconsole.market.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NexMarketCurveScheduler {
    private final OpsNexMarketService marketService;

    public NexMarketCurveScheduler(OpsNexMarketService marketService) {
        this.marketService = marketService;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void advanceUtcDailyFrame() {
        marketService.advanceScheduledFrame();
    }
}
