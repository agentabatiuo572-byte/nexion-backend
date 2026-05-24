package ffdd.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.openapi.domain.WebhookDelivery;
import ffdd.openapi.domain.WebhookSubscription;
import ffdd.openapi.mapper.WebhookDeliveryMapper;
import ffdd.openapi.mapper.WebhookSubscriptionMapper;
import ffdd.openapi.webhook.WebhookHttpClient;
import ffdd.openapi.webhook.WebhookHttpRequest;
import ffdd.openapi.webhook.WebhookHttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WebhookDeliveryService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_DEAD = "DEAD";

    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final WebhookHttpClient httpClient;
    private final int maxRetries;
    private final long initialBackoffSeconds;

    public WebhookDeliveryService(
            WebhookDeliveryMapper deliveryMapper,
            WebhookSubscriptionMapper subscriptionMapper,
            WebhookHttpClient httpClient,
            @Value("${nexion.openapi.webhook.delivery.max-retries:5}") int maxRetries,
            @Value("${nexion.openapi.webhook.delivery.initial-backoff-seconds:60}") long initialBackoffSeconds) {
        this.deliveryMapper = deliveryMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.httpClient = httpClient;
        this.maxRetries = Math.max(1, maxRetries);
        this.initialBackoffSeconds = Math.max(1, initialBackoffSeconds);
    }

    public WebhookDeliveryPublishResponse publishPending(int limit) {
        List<WebhookDelivery> deliveries = listDue(limit);
        int succeeded = 0;
        int failed = 0;
        int dead = 0;
        for (WebhookDelivery delivery : deliveries) {
            WebhookSubscription subscription = subscriptionMapper.selectById(delivery.getSubscriptionId());
            if (subscription == null || !STATUS_ACTIVE.equals(subscription.getStatus())) {
                markDead(delivery, null, "webhook subscription inactive");
                dead++;
                continue;
            }
            try {
                WebhookHttpResponse response = httpClient.post(buildRequest(delivery, subscription));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    markSuccess(delivery, response.statusCode());
                    succeeded++;
                } else if (markFailure(delivery, response.statusCode(), response.body())) {
                    dead++;
                } else {
                    failed++;
                }
            } catch (RuntimeException ex) {
                if (markFailure(delivery, null, ex.getMessage())) {
                    dead++;
                } else {
                    failed++;
                }
            }
        }
        return new WebhookDeliveryPublishResponse(deliveries.size(), succeeded, failed, dead);
    }

    public List<WebhookDelivery> listByStatus(String status, Long appId, String eventType, int limit) {
        LambdaQueryWrapper<WebhookDelivery> wrapper = new LambdaQueryWrapper<WebhookDelivery>()
                .eq(StringUtils.hasText(status), WebhookDelivery::getStatus, status)
                .eq(appId != null, WebhookDelivery::getAppId, appId)
                .eq(StringUtils.hasText(eventType), WebhookDelivery::getEventType, eventType)
                .eq(WebhookDelivery::getIsDeleted, 0)
                .orderByDesc(WebhookDelivery::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return deliveryMapper.selectList(wrapper);
    }

    public Map<String, Object> summary() {
        return Map.of(
                "pending", count(STATUS_PENDING),
                "failed", count(STATUS_FAILED),
                "success", count(STATUS_SUCCESS),
                "dead", count(STATUS_DEAD));
    }

    private List<WebhookDelivery> listDue(int limit) {
        LocalDateTime now = LocalDateTime.now();
        return deliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                .in(WebhookDelivery::getStatus, STATUS_PENDING, STATUS_FAILED)
                .eq(WebhookDelivery::getIsDeleted, 0)
                .and(wrapper -> wrapper
                        .isNull(WebhookDelivery::getNextRetryAt)
                        .or()
                        .le(WebhookDelivery::getNextRetryAt, now))
                .orderByAsc(WebhookDelivery::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit)));
    }

    private WebhookHttpRequest buildRequest(WebhookDelivery delivery, WebhookSubscription subscription) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String payload = delivery.getPayload() == null ? "{}" : delivery.getPayload();
        String stringToSign = delivery.getId() + "\n"
                + delivery.getEventType() + "\n"
                + timestamp + "\n"
                + sha256(payload);
        return new WebhookHttpRequest(subscription.getCallbackUrl(), Map.of(
                "Content-Type", "application/json",
                "X-Nexion-Webhook-Id", String.valueOf(delivery.getId()),
                "X-Nexion-Event-Type", delivery.getEventType(),
                "X-Nexion-Timestamp", timestamp,
                "X-Nexion-Signature", hmacSha256(subscription.getSecret(), stringToSign)), payload);
    }

    private void markSuccess(WebhookDelivery delivery, int statusCode) {
        WebhookDelivery patch = new WebhookDelivery();
        patch.setId(delivery.getId());
        patch.setStatus(STATUS_SUCCESS);
        patch.setRetryCount(attempts(delivery) + 1);
        patch.setLastStatusCode(statusCode);
        patch.setLastError(null);
        patch.setNextRetryAt(null);
        patch.setDeliveredAt(LocalDateTime.now());
        deliveryMapper.updateById(patch);
    }

    private boolean markFailure(WebhookDelivery delivery, Integer statusCode, String error) {
        int attempts = attempts(delivery) + 1;
        boolean dead = attempts >= maxRetries;
        if (dead) {
            markDead(delivery, statusCode, error);
            return true;
        }
        WebhookDelivery patch = new WebhookDelivery();
        patch.setId(delivery.getId());
        patch.setStatus(STATUS_FAILED);
        patch.setRetryCount(attempts);
        patch.setLastStatusCode(statusCode);
        patch.setLastError(truncate(error, 512));
        patch.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempts)));
        deliveryMapper.updateById(patch);
        return false;
    }

    private void markDead(WebhookDelivery delivery, Integer statusCode, String error) {
        WebhookDelivery patch = new WebhookDelivery();
        patch.setId(delivery.getId());
        patch.setStatus(STATUS_DEAD);
        patch.setRetryCount(attempts(delivery) + 1);
        patch.setLastStatusCode(statusCode);
        patch.setLastError(truncate(error, 512));
        patch.setNextRetryAt(null);
        deliveryMapper.updateById(patch);
    }

    private long count(String status) {
        return deliveryMapper.selectCount(new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getStatus, status)
                .eq(WebhookDelivery::getIsDeleted, 0));
    }

    private int attempts(WebhookDelivery delivery) {
        return delivery.getRetryCount() == null ? 0 : delivery.getRetryCount();
    }

    private long backoffSeconds(int attempts) {
        int exponent = Math.min(Math.max(0, attempts - 1), 10);
        return initialBackoffSeconds * (1L << exponent);
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private String truncate(String value, int maxLength) {
        String message = StringUtils.hasText(value) ? value : "unknown error";
        return message.length() <= maxLength ? message : message.substring(0, maxLength);
    }

    private String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException("Unable to sign webhook delivery");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException("Unable to hash webhook payload");
        }
    }
}
