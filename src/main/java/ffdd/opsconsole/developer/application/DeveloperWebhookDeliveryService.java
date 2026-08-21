package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookDeliveryMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookMapper;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ffdd.opsconsole.shared.exception.BizException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeveloperWebhookDeliveryService {
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long MAX_BACKOFF_SECONDS = 3600;
    private final AppDeveloperWebhookMapper webhooks;
    private final AppDeveloperWebhookDeliveryMapper deliveries;
    private final DeveloperWebhookDeliveryTransport transport;
    private final Clock clock;
    private final Environment environment;
    private final WebhookSecretCodec secrets;

    /** Publishes one durable delivery per active matching endpoint; the unique key makes retries safe. */
    @Transactional
    public int enqueue(Long userId, String sourceEnvironment, String runId, String eventId, String eventType,
                       String rawPayload, Long ignoredWebhookId) {
        if (userId == null || eventId == null || eventType == null || rawPayload == null
                || !AppDeveloperWebhookService.eventAllowlist().contains(eventType)) return 0;
        List<Long> endpointIds = webhooks.activeMatchingIds(userId, sourceEnvironment, runId, eventType);
        int inserted = 0;
        for (Long id : endpointIds) {
            inserted += deliveries.insertIgnore(new AppDeveloperWebhookDeliveryMapper.DeliveryWrite(id, userId,
                    sourceEnvironment, runId, eventId, eventType, rawPayload, maxAttempts(), now())) > 0 ? 1 : 0;
        }
        return inserted;
    }

    /** Invoked by the scheduler in production; unit tests call it directly with a deterministic transport. */
    @Scheduled(fixedDelayString = "${nexion.developer.webhooks.worker-delay-ms:5000}",
            scheduler = "developerWebhookTaskScheduler")
    public void scheduledDelivery() { deliverDue(50); }

    public void deliverDue(int limit) {
        LocalDateTime now = now();
        long leaseSeconds = Math.max(1L, environment.getProperty(
                "nexion.developer.webhooks.delivery-lease-seconds", Long.class, 120L));
        // A process can die after claiming a row but before finalizing it. Reclaim only rows whose
        // durable lease is stale; this makes restart recovery explicit without allowing a live worker
        // to be double-claimed during its lease.
        try {
            deliveries.reclaimStaleDelivering(now.minusSeconds(leaseSeconds), now);
        } catch (RuntimeException ex) {
            log.error("developer webhook stale lease reclaim failed error={}", safeError(ex));
        }
        List<AppDeveloperWebhookDeliveryMapper.DeliveryRow> due;
        try {
            due = deliveries.claimDue(Math.max(1, Math.min(limit, 100)), now, null);
        } catch (RuntimeException ex) {
            log.error("developer webhook due delivery claim failed error={}", safeError(ex));
            return;
        }
        for (var delivery : due) {
            try {
                // Each delivery is deliberately isolated from the batch. A mapper/transport failure for one
                // endpoint must not roll back or prevent the remaining claimed deliveries from being finalized.
                deliverOne(delivery);
            } catch (RuntimeException ex) {
                // Known transport failures are finalized by deliverOne. This guard covers persistence or mapper
                // failures (including a malformed legacy row) so the scheduled worker remains alive for the batch.
                // The row stays DELIVERING and is visible to operational recovery rather than being retried
                // against a transaction already marked rollback-only.
                log.error("developer webhook delivery attempt failed deliveryId={} eventId={} error={}",
                        delivery.id(), delivery.eventId(), safeError(ex));
            }
        }
    }

    private void deliverOne(AppDeveloperWebhookDeliveryMapper.DeliveryRow delivery) {
        int attempt = delivery.attemptCount() + 1;
        LocalDateTime at = now();
        var endpoint = webhooks.byIdForDelivery(delivery.webhookId());
        if (endpoint == null || !sameScope(endpoint, delivery)) {
            deliveries.markNotDelivered(delivery.id(), "SCOPED_MISMATCH", at);
            return;
        }
        if (!"ACTIVE".equals(endpoint.status())) {
            deliveries.markNotDelivered(delivery.id(), "ENDPOINT_DISABLED", at);
            return;
        }
        URI uri;
        DeveloperWebhookDeliveryTransport.Response response;
        try {
            uri = DeveloperWebhookUrlValidator.validate(endpoint.url(), environment);
            String secret = secrets.decode(endpoint.secretCiphertext());
            String timestamp = String.valueOf(clock.instant().getEpochSecond());
            String signature = signature(secret, delivery.webhookId(), delivery.eventType(), timestamp, delivery.payloadJson());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Nexion-Webhook-Id", String.valueOf(delivery.webhookId()));
            headers.put("X-Nexion-Delivery-Id", String.valueOf(delivery.id()));
            headers.put("X-Nexion-Event", delivery.eventType());
            headers.put("X-Nexion-Event-Type", delivery.eventType());
            headers.put("X-Nexion-Timestamp", timestamp);
            headers.put("X-Nexion-Signature", signature);
            response = transport.send(uri.toString(), headers, delivery.payloadJson());
        } catch (BizException ex) {
            deliveries.markDead(delivery.id(), attempt, null, "URL_NOT_ALLOWED", at);
            return;
        } catch (IllegalArgumentException ex) {
            deliveries.markDead(delivery.id(), attempt, null, "SECRET_UNAVAILABLE", at);
            return;
        } catch (Exception ex) {
            String error = ex instanceof HttpTimeoutException ? "TIMEOUT" : safeError(ex);
            fail(delivery, attempt, null, error, at);
            return;
        }
        // Keep persistence finalization outside the transport catch block. If the database rejects one state
        // transition, the outer batch guard logs that row and proceeds; it must not be misclassified as a retry
        // response or invoke the failing terminal transition a second time.
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            deliveries.markSucceeded(delivery.id(), attempt, response.statusCode(), at);
        } else {
            fail(delivery, attempt, response.statusCode(), "HTTP_" + response.statusCode(), at);
        }
    }

    private boolean sameScope(AppDeveloperWebhookMapper.WebhookRow endpoint,
                              AppDeveloperWebhookDeliveryMapper.DeliveryRow delivery) {
        return endpoint.userId().equals(delivery.userId())
                && java.util.Objects.equals(endpoint.sourceEnvironment(), delivery.sourceEnvironment())
                && java.util.Objects.equals(endpoint.runId(), delivery.runId());
    }

    private void fail(AppDeveloperWebhookDeliveryMapper.DeliveryRow delivery, int attempt, Integer code, String error, LocalDateTime at) {
        if (attempt >= Math.max(1, delivery.maxAttempts())) deliveries.markDead(delivery.id(), attempt, code, error, at);
        else deliveries.markRetry(delivery.id(), attempt, code, error, at.plusSeconds(backoff(attempt)));
    }

    private long backoff(int attempt) { return Math.min(MAX_BACKOFF_SECONDS, 5L * (1L << Math.min(10, Math.max(0, attempt - 1)))); }
    private int maxAttempts() { return Math.max(1, environment.getProperty("nexion.developer.webhooks.max-attempts", Integer.class, DEFAULT_MAX_ATTEMPTS)); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), clock.getZone()); }
    private String safeError(Exception ex) {
        if (ex.getMessage() == null) return ex.getClass().getSimpleName();
        String sanitized = ex.getMessage().replaceAll("(?i)(secret|token|key)=[^& ]+", "$1=[REDACTED]");
        return sanitized.length() <= 120 ? sanitized : sanitized.substring(0, 120);
    }

    static String signature(String secret, Long webhookId, String eventType, String timestamp, String rawBody) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody.getBytes(StandardCharsets.UTF_8)));
            String toSign = webhookId + "\n" + eventType + "\n" + timestamp + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + HexFormat.of().formatHex(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("WEBHOOK_SIGNATURE_FAILED", ex); }
    }
}
