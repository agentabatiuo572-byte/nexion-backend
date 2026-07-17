package ffdd.opsconsole.risk.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MultiAccountClusterScheduler {
    private final MultiAccountClusterBatchService batchService;

    @Scheduled(
            fixedDelayString = "${nexion.risk.k1-cluster-delay-ms:60000}",
            initialDelayString = "${nexion.risk.k1-cluster-initial-delay-ms:5000}")
    public void rebuild() {
        try {
            batchService.rebuild();
        } catch (RuntimeException ex) {
            log.error("K1 multi-account graph rebuild failed", ex);
        }
    }
}
