package ffdd.opsconsole.content.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SupportLeaderboardSamplingScheduler {
    private static final Logger log=LoggerFactory.getLogger(SupportLeaderboardSamplingScheduler.class);
    private final SupportLeaderboardSamplingService sampling;
    public SupportLeaderboardSamplingScheduler(SupportLeaderboardSamplingService sampling){this.sampling=sampling;}
    @Scheduled(initialDelay=30_000,fixedDelay=30_000,scheduler="supportLeaderboardTaskScheduler")
    public void poll(){var result=sampling.sample();
        if(!"PROFILE_BLOCKED".equals(result.status()) && (!"IDLE".equals(result.status()) || result.deferred()>0))
            log.info("Leaderboard sampling status={} planned={} scanned={} due={} committed={} superseded={} failed={} deferred={} retired={}",
                result.status(),result.planned(),result.scanned(),result.due(),result.committed(),result.superseded(),result.failed(),result.deferred(),result.retired());
    }
}
