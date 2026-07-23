package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TopupRiskLockScheduler {
    private final TopupRiskLockSynchronizationService synchronizationService;
    private final PlatformConfigFacade configFacade;

    @Scheduled(fixedDelayString = "${nexion.finance.d1-risk-lock-delay-ms:60000}")
    public void synchronizeAutomaticLocks() {
        try {
            synchronizationService.synchronize(
                    intConfig("finance.topup.card.cardRetryLimit", 5, 3, 10),
                    intConfig("finance.topup.card.cardLockHours", 24, 1, 72));
        } catch (RuntimeException ex) {
            log.error("D1 automatic BIN/IP/device lock synchronization failed", ex);
        }
    }

    private int intConfig(String key, int fallback, int min, int max) {
        int value = configFacade.activeValue(key).map(raw -> {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }).orElse(fallback);
        return Math.max(min, Math.min(max, value));
    }
}
