package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookDeliveryMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookMapper;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DeveloperWebhookDeliveryServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void signsRawBodyAndMarksSuccessfulDelivery() throws Exception {
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        var row = webhook(11L, "https://hooks.example.com/nexion", "ACTIVE", "secret-cipher", "PRODUCTION", "");
        var delivery = delivery(101L, 11L, 7L, "order.updated", "{\"id\":7}", 0, "PENDING");
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(delivery));
        when(mapper.byIdForDelivery(11L)).thenReturn(row);
        when(transport.send(any(), any(), any())).thenReturn(new DeveloperWebhookDeliveryTransport.Response(204, ""));
        var service = new DeveloperWebhookDeliveryService(mapper, deliveries, transport, CLOCK,
                new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"),
                secret -> "top-secret");

        service.deliverDue(10);

        verify(transport).send(eq("https://hooks.example.com/nexion"), argThat(h ->
                        h.get("X-Nexion-Webhook-Id").equals("11")
                        && h.get("X-Nexion-Delivery-Id").equals("101")
                        && h.get("X-Nexion-Event").equals("order.updated")
                        && h.get("X-Nexion-Signature").equals(DeveloperWebhookDeliveryService.signature(
                        "top-secret", 11L, "order.updated", String.valueOf(CLOCK.instant().getEpochSecond()),
                        "{\"id\":7}"))), eq("{\"id\":7}"));
        verify(deliveries).markSucceeded(eq(101L), eq(1), eq(204), any());
    }

    @Test
    void retriesNon2xxAndDeadLettersAtAttemptLimit() throws Exception {
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(
                delivery(1L, 11L, 1L, "order.updated", "{}", 0, "PENDING"),
                delivery(2L, 11L, 2L, "order.updated", "{}", 4, "RETRYING")));
        when(mapper.byIdForDelivery(11L)).thenReturn(webhook(11L, "https://hooks.example.com", "ACTIVE", "secret-cipher", "PRODUCTION", ""));
        when(transport.send(any(), any(), any())).thenReturn(new DeveloperWebhookDeliveryTransport.Response(500, "bad"));
        var service = service(mapper, deliveries, transport);

        service.deliverDue(10);

        verify(deliveries).markRetry(eq(1L), eq(1), eq(500), eq("HTTP_500"), any());
        verify(deliveries).markDead(eq(2L), eq(5), eq(500), eq("HTTP_500"), any());
    }

    @Test
    void timeoutIsRetriedAndDeliveryIsIdempotentlyEnqueued() throws Exception {
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(deliveries.insertIgnore(any())).thenReturn(0);
        doThrow(new java.net.http.HttpTimeoutException("timeout")).when(transport).send(any(), any(), any());
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(
                delivery(3L, 11L, 3L, "order.updated", "{}", 0, "PENDING")));
        when(mapper.byIdForDelivery(11L)).thenReturn(webhook(11L, "https://hooks.example.com", "ACTIVE", "secret-cipher", "PRODUCTION", ""));
        var service = service(mapper, deliveries, transport);

        assertThat(service.enqueue(7L, "PRODUCTION", "", "evt-1", "order.updated", "{}", 11L)).isEqualTo(0);
        service.deliverDue(10);
        verify(deliveries).markRetry(eq(3L), eq(1), isNull(), eq("TIMEOUT"), any());
    }

    @Test
    void rejectsPrivateMetadataAndDangerousSchemeBeforeTransport() {
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(
                delivery(1L, 11L, 1L, "order.updated", "{}", 0, "PENDING")));
        when(mapper.byIdForDelivery(11L)).thenReturn(webhook(11L, "file:///etc/passwd", "ACTIVE", "secret-cipher", "PRODUCTION", ""));
        var service = service(mapper, deliveries, transport);

        service.deliverDue(10);

        verifyNoInteractions(transport);
        verify(deliveries).markDead(eq(1L), eq(1), isNull(), eq("URL_NOT_ALLOWED"), any());
    }

    @Test
    void onePersistenceFailureDoesNotStopTheRemainingClaimedDeliveries() throws Exception {
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(
                delivery(1L, 11L, 1L, "order.updated", "{}", 4, "PENDING"),
                delivery(2L, 11L, 2L, "order.updated", "{}", 0, "PENDING")));
        when(mapper.byIdForDelivery(11L)).thenReturn(webhook(11L, "https://hooks.example.com", "ACTIVE",
                "secret-cipher", "PRODUCTION", ""));
        when(transport.send(any(), any(), any())).thenReturn(new DeveloperWebhookDeliveryTransport.Response(500, "bad"));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("legacy next_retry_at constraint"))
                .when(deliveries).markDead(eq(1L), eq(5), eq(500), eq("HTTP_500"), any());
        var service = service(mapper, deliveries, transport);

        service.deliverDue(10);

        verify(deliveries).markDead(eq(1L), eq(5), eq(500), eq("HTTP_500"), any());
        verify(deliveries).markRetry(eq(2L), eq(1), eq(500), eq("HTTP_500"), any());
    }

    @Test
    void disabledEndpointNeverDeliversAndDoesNotCrossAccountScope() {
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(
                delivery(1L, 11L, 9L, "order.updated", "{}", 0, "PENDING")));
        when(mapper.byIdForDelivery(11L)).thenReturn(webhook(11L, "https://hooks.example.com", "DISABLED", "secret-cipher", "PRODUCTION", ""));
        var service = service(mapper, deliveries, transport);

        service.deliverDue(10);

        verifyNoInteractions(transport);
        verify(deliveries).markNotDelivered(eq(1L), eq("ENDPOINT_DISABLED"), any());
    }

    @Test
    void redactsLongSensitiveTransportErrorsUsingSanitizedLength() throws Exception {
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(
                delivery(1L, 11L, 1L, "order.updated", "{}", 0, "PENDING")));
        when(mapper.byIdForDelivery(11L)).thenReturn(webhook(11L, "https://hooks.example.com", "ACTIVE",
                "secret-cipher", "PRODUCTION", ""));
        doThrow(new IllegalStateException("secret=" + "s".repeat(500) + " token=" + "t".repeat(500)))
                .when(transport).send(any(), any(), any());

        service(mapper, deliveries, transport).deliverDue(10);

        var error = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deliveries).markRetry(eq(1L), eq(1), isNull(), error.capture(), any());
        assertThat(error.getValue()).hasSizeLessThanOrEqualTo(120)
                .doesNotContain("s".repeat(20)).doesNotContain("t".repeat(20));
    }

    @Test
    void usesBusinessZoneForClaimLeaseAndRetryTimes() throws Exception {
        Clock businessClock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(deliveries.claimDue(anyInt(), any(), any())).thenReturn(List.of(
                delivery(1L, 11L, 1L, "order.updated", "{}", 0, "PENDING")));
        when(mapper.byIdForDelivery(11L)).thenReturn(webhook(11L, "https://hooks.example.com", "ACTIVE",
                "secret-cipher", "PRODUCTION", ""));
        when(transport.send(any(), any(), any())).thenReturn(new DeveloperWebhookDeliveryTransport.Response(500, "bad"));
        var service = new DeveloperWebhookDeliveryService(mapper, deliveries, transport, businessClock,
                new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com")
                        .withProperty("nexion.developer.webhooks.delivery-lease-seconds", "120"),
                secret -> "top-secret");

        service.deliverDue(10);

        LocalDateTime expectedNow = LocalDateTime.of(2026, 8, 17, 8, 0);
        verify(deliveries).reclaimStaleDelivering(expectedNow.minusMinutes(2), expectedNow);
        verify(deliveries).claimDue(eq(10), eq(expectedNow), isNull());
        verify(deliveries).markRetry(eq(1L), eq(1), eq(500), eq("HTTP_500"), eq(expectedNow.plusSeconds(5)));
    }

    @Test
    void usesBusinessZoneForEnqueueDueTime() {
        Clock businessClock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        var mapper = mock(AppDeveloperWebhookMapper.class);
        var deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        var transport = mock(DeveloperWebhookDeliveryTransport.class);
        when(mapper.activeMatchingIds(7L, "PRODUCTION", "", "order.updated")).thenReturn(List.of(11L));
        when(deliveries.insertIgnore(any())).thenReturn(1);
        var service = new DeveloperWebhookDeliveryService(mapper, deliveries, transport, businessClock,
                new MockEnvironment(), secret -> "top-secret");

        assertThat(service.enqueue(7L, "PRODUCTION", "", "evt-1", "order.updated", "{}", null)).isEqualTo(1);

        var write = org.mockito.ArgumentCaptor.forClass(AppDeveloperWebhookDeliveryMapper.DeliveryWrite.class);
        verify(deliveries).insertIgnore(write.capture());
        assertThat(write.getValue().nextRetryAt()).isEqualTo(LocalDateTime.of(2026, 8, 17, 8, 0));
    }

    private DeveloperWebhookDeliveryService service(AppDeveloperWebhookMapper mapper,
                                                     AppDeveloperWebhookDeliveryMapper deliveries,
                                                     DeveloperWebhookDeliveryTransport transport) {
        return new DeveloperWebhookDeliveryService(mapper, deliveries, transport, CLOCK,
                new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"),
                secret -> "top-secret");
    }

    private AppDeveloperWebhookMapper.WebhookRow webhook(Long id, String url, String status, String ciphertext,
                                                          String environment, String runId) {
        return new AppDeveloperWebhookMapper.WebhookRow(id, 7L, "hash", "orders", url,
                "[\"order.updated\"]", status, "NOT_DELIVERED", 0L, null, null, ciphertext,
                LocalDateTime.now(), LocalDateTime.now(), environment, runId);
    }

    private AppDeveloperWebhookDeliveryMapper.DeliveryRow delivery(Long id, Long webhookId, Long eventId,
                                                                     String eventType, String payload, int attempts,
                                                                     String status) {
        return new AppDeveloperWebhookDeliveryMapper.DeliveryRow(id, webhookId, 7L, "PRODUCTION", "", "evt-" + eventId,
                eventType, payload, status, attempts, 5, null, null, LocalDateTime.now(), LocalDateTime.now());
    }
}
