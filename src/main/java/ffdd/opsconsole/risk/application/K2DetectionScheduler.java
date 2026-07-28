package ffdd.opsconsole.risk.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class K2DetectionScheduler {
    private final OpsRiskService riskService;

    @Scheduled(
            fixedDelayString = "${nexion.risk.k2-detection-delay-ms:60000}",
            initialDelayString = "${nexion.risk.k2-detection-initial-delay-ms:10000}")
    public void refresh() {
        try {
            riskService.refreshK2AuthoritativeProjection();
        } catch (RuntimeException ex) {
            log.error("K2 authoritative runtime detection failed", ex);
        }
    }
}
