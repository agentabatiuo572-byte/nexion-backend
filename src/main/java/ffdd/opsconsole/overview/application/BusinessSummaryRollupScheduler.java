package ffdd.opsconsole.overview.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BusinessSummaryRollupScheduler {
    private final BusinessSummaryRollupService rollups;

    @Scheduled(fixedDelayString="${nexion.overview.rollup-delay-ms:60000}",initialDelayString="${nexion.overview.rollup-initial-delay-ms:1000}")
    public void refresh() {
        rollups.refresh();
    }
}
