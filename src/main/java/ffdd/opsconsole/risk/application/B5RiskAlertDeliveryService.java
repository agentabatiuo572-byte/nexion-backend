package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper.AlertDeliveryRecord;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class B5RiskAlertDeliveryService {
    private static final String CHANNELS_KEY = "risk.alert-subscription.channels";
    private static final String WEBHOOK_KEY = "risk.alert-subscription.webhook-url";
    private static final String SUBSCRIBER_KEY = "risk.alert-subscription.subscriber";
    static final String WEBHOOK_ALLOWLIST_KEY = "risk.alert-subscription.webhook-host-allowlist";
    static final String WEBHOOK_EGRESS_PROXY_KEY = "risk.alert-subscription.webhook-egress-proxy";
    static final int MAX_RETRIES = 5;
    private static final Set<String> CHANNELS = Set.of("inApp", "email", "webhook");
    private final B5RiskRadarMapper mapper;
    private final PlatformConfigFacade config;
    private final B5RiskAlertDeliveryFinalizer finalizer;

    @Scheduled(fixedDelayString = "${nexion.b5.alert-delivery-delay-ms:30000}")
    public void scheduledDispatch() {
        scanAndDispatch();
    }

    public void scanAndDispatch() {
        String subscriber = config.activeValue(SUBSCRIBER_KEY).orElse("").trim();
        if (!StringUtils.hasText(subscriber)) return;
        Set<String> configured = parseChannels(config.activeValue(CHANNELS_KEY).orElse(""));
        if (!CHANNELS.containsAll(configured)) throw new IllegalStateException("B5_ALERT_CHANNEL_CONFIG_INVALID");
        if (configured.contains("email")) {
            throw new IllegalStateException("B5_EMAIL_PROVIDER_UNAVAILABLE");
        }
        for (String channel : configured) {
            if (!StringUtils.hasText(channel)) continue;
            for (String signalNo : mapper.undeliveredSignalNos(subscriber, channel, 500)) {
                mapper.enqueueAlertDelivery(signalNo, subscriber, channel);
            }
        }
        for (AlertDeliveryRecord delivery : mapper.dueAlertDeliveries(100)) {
            try { deliver(delivery); } catch (RuntimeException ignored) { /* one item must not starve the batch */ }
        }
    }

    void dispatchDueForTest() {
        for (AlertDeliveryRecord delivery : mapper.dueAlertDeliveries(100)) deliver(delivery);
    }

    private void deliver(AlertDeliveryRecord delivery) {
        if (!finalizer.claim(delivery.id())) return;
        DeliveryOutcome outcome = null;
        String error = null;
        try {
            outcome = switch (delivery.channel()) {
                case "inApp" -> new DeliveryOutcome("durable-inbox", "inapp:" + delivery.id());
                case "webhook" -> deliverWebhook(delivery);
                case "email" -> throw new IllegalStateException("B5_EMAIL_PROVIDER_UNAVAILABLE");
                default -> throw new IllegalStateException("B5_ALERT_CHANNEL_INVALID");
            };
        } catch (Exception ex) {
            error = safeError(ex);
        }
        boolean delivered = outcome != null;
        if (delivered) finalizer.complete(delivery, outcome.source(), outcome.receipt());
        else finalizer.fail(delivery, error == null ? "B5_ALERT_DELIVERY_FAILED" : error, MAX_RETRIES);
    }

    private DeliveryOutcome deliverWebhook(AlertDeliveryRecord delivery) throws Exception {
        String raw = config.activeValue(WEBHOOK_KEY).orElse("");
        URI uri = URI.create(raw);
        requireAllowedPublicHttps(uri, webhookAllowlist());
        // Delivery is at-least-once. Receivers deduplicate every retry with this stable database id.
        String deliveryKey = "b5-alert-delivery-" + delivery.id();
        String payload = "{\"deliveryId\":\"" + deliveryKey + "\",\"signalNo\":\""
                + json(delivery.signalNo()) + "\",\"subscriber\":\""
                + json(delivery.subscriber()) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", deliveryKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<Void> response = webhookHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("B5_WEBHOOK_HTTP_" + response.statusCode());
        }
        return new DeliveryOutcome("provider", "http:" + response.statusCode());
    }

    HttpClient webhookHttpClient() {
        String rawProxy = config.activeValue(WEBHOOK_EGRESS_PROXY_KEY).orElse("").trim();
        if (!StringUtils.hasText(rawProxy)) {
            throw new IllegalStateException("B5_WEBHOOK_EGRESS_PROXY_UNAVAILABLE");
        }
        URI proxy = URI.create(rawProxy);
        if (!"http".equalsIgnoreCase(proxy.getScheme()) || !StringUtils.hasText(proxy.getHost())
                || proxy.getPort() <= 0 || proxy.getUserInfo() != null
                || (StringUtils.hasText(proxy.getPath()) && !"/".equals(proxy.getPath()))) {
            throw new IllegalStateException("B5_WEBHOOK_EGRESS_PROXY_INVALID");
        }
        // HTTPS is always CONNECTed through the controlled egress proxy. The application never
        // performs the post-validation hostname resolution, closing DNS-rebinding TOCTOU.
        return HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private Set<String> parseChannels(String value) {
        if (!StringUtils.hasText(value)) return Set.of();
        return Set.of(value.split(","));
    }

    public static void requireAllowedPublicHttps(URI uri, Set<String> allowedHosts) throws Exception {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())
                || uri.getUserInfo() != null) throw new IllegalArgumentException("B5_WEBHOOK_URL_INVALID");
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (allowedHosts == null || !allowedHosts.contains(host)) {
            throw new IllegalArgumentException("B5_WEBHOOK_HOST_NOT_ALLOWED");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("B5_WEBHOOK_PRIVATE_ADDRESS_FORBIDDEN");
            }
        }
    }

    Set<String> webhookAllowlist() {
        String value = config.activeValue(WEBHOOK_ALLOWLIST_KEY).orElse("");
        if (!StringUtils.hasText(value)) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText)
                .map(valuePart -> valuePart.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String safeError(Exception ex) {
        String value = ex.getMessage();
        if (!StringUtils.hasText(value)) value = ex.getClass().getSimpleName();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record DeliveryOutcome(String source, String receipt) {}
}
