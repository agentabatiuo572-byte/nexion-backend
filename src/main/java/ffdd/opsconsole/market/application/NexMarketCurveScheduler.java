package ffdd.opsconsole.market.application;


import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NexMarketCurveScheduler {
    private final OpsNexMarketService marketService;

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void advanceUtcDailyFrame() {
        marketService.advanceScheduledFrame();
    }
}
