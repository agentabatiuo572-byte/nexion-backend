package ffdd.openapi.worker;

import ffdd.openapi.service.WebhookDeliveryPublishResponse;
import ffdd.openapi.service.WebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final WebhookDeliveryService deliveryService;
    private final boolean enabled;
    private final int batchSize;

    public WebhookDeliveryWorker(
            WebhookDeliveryService deliveryService,
            @Value("${nexion.openapi.webhook.delivery.enabled:false}") boolean enabled,
            @Value("${nexion.openapi.webhook.delivery.batch-size:20}") int batchSize) {
        this.deliveryService = deliveryService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${nexion.openapi.webhook.delivery.initial-delay-ms:10000}",
            fixedDelayString = "${nexion.openapi.webhook.delivery.fixed-delay-ms:10000}")
    public void deliverScheduled() {
        if (!enabled) {
            return;
        }
        try {
            WebhookDeliveryPublishResponse response = deliveryService.publishPending(batchSize);
            if (response.getScanned() > 0) {
                log.info("OpenAPI webhook delivery scanned={}, succeeded={}, failed={}, dead={}",
                        response.getScanned(), response.getSucceeded(), response.getFailed(), response.getDead());
            }
        } catch (RuntimeException ex) {
            log.warn("OpenAPI webhook delivery worker failed: {}", ex.getMessage());
        }
    }
}
