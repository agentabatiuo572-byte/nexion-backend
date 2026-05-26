package ffdd.notification.worker;

import ffdd.notification.dto.NotificationPushResult;
import ffdd.notification.service.NotificationPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationPushWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationPushWorker.class);

    private final NotificationPushService notificationPushService;
    private final boolean enabled;
    private final int batchSize;

    public NotificationPushWorker(
            NotificationPushService notificationPushService,
            @Value("${nexion.notification.push.worker.enabled:false}") boolean enabled,
            @Value("${nexion.notification.push.worker.batch-size:100}") int batchSize) {
        this.notificationPushService = notificationPushService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${nexion.notification.push.worker.fixed-delay-ms:5000}")
    public void dispatchPendingPushes() {
        if (!enabled) {
            return;
        }
        try {
            NotificationPushResult result = notificationPushService.pushPending(batchSize);
            if (result.getScanned() > 0) {
                log.info("Notification push worker scanned={}, sent={}, failed={}, dead={}",
                        result.getScanned(), result.getSent(), result.getFailed(), result.getDead());
            }
        } catch (RuntimeException ex) {
            log.warn("Notification push worker failed error={}", ex.getMessage());
        }
    }
}
