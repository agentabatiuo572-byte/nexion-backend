package ffdd.openapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.openapi.domain.WebhookDelivery;
import ffdd.openapi.domain.WebhookSubscription;
import ffdd.openapi.mapper.WebhookDeliveryMapper;
import ffdd.openapi.mapper.WebhookSubscriptionMapper;
import ffdd.openapi.webhook.WebhookHttpClient;
import ffdd.openapi.webhook.WebhookHttpRequest;
import ffdd.openapi.webhook.WebhookHttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebhookDeliveryServiceTest {
    private final WebhookDeliveryMapper deliveryMapper = mock(WebhookDeliveryMapper.class);
    private final WebhookSubscriptionMapper subscriptionMapper = mock(WebhookSubscriptionMapper.class);
    private final WebhookHttpClient httpClient = mock(WebhookHttpClient.class);
    private final WebhookDeliveryService deliveryService =
            new WebhookDeliveryService(deliveryMapper, subscriptionMapper, httpClient, 3, 30);

    @Test
    void marksDeliverySuccessAfterHttp2xx() {
        WebhookDelivery delivery = delivery("PENDING", 0);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(subscriptionMapper.selectById(10L)).thenReturn(subscription());
        when(httpClient.post(any())).thenReturn(new WebhookHttpResponse(204, ""));

        WebhookDeliveryPublishResponse response = deliveryService.publishPending(10);

        assertThat(response.getScanned()).isEqualTo(1);
        assertThat(response.getSucceeded()).isEqualTo(1);
        ArgumentCaptor<WebhookDelivery> patchCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(patchCaptor.getValue().getLastStatusCode()).isEqualTo(204);
    }

    @Test
    void signsWebhookRequestHeaders() {
        WebhookDelivery delivery = delivery("PENDING", 0);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(subscriptionMapper.selectById(10L)).thenReturn(subscription());
        when(httpClient.post(any())).thenReturn(new WebhookHttpResponse(200, "ok"));

        deliveryService.publishPending(10);

        ArgumentCaptor<WebhookHttpRequest> requestCaptor = ArgumentCaptor.forClass(WebhookHttpRequest.class);
        verify(httpClient).post(requestCaptor.capture());
        WebhookHttpRequest request = requestCaptor.getValue();
        assertThat(request.url()).isEqualTo("https://merchant.example/webhook");
        assertThat(request.headers()).containsKeys(
                "X-Nexion-Webhook-Id",
                "X-Nexion-Event-Type",
                "X-Nexion-Timestamp",
                "X-Nexion-Signature");
        assertThat(request.headers().get("X-Nexion-Signature")).isNotBlank();
    }

    @Test
    void schedulesRetryWhenHttpFailsBelowMaxRetries() {
        WebhookDelivery delivery = delivery("FAILED", 1);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(subscriptionMapper.selectById(10L)).thenReturn(subscription());
        when(httpClient.post(any())).thenReturn(new WebhookHttpResponse(500, "down"));

        WebhookDeliveryPublishResponse response = deliveryService.publishPending(10);

        assertThat(response.getFailed()).isEqualTo(1);
        ArgumentCaptor<WebhookDelivery> patchCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(patchCaptor.getValue().getRetryCount()).isEqualTo(2);
        assertThat(patchCaptor.getValue().getNextRetryAt()).isNotNull();
    }

    @Test
    void marksDeadWhenMaxRetriesReached() {
        WebhookDelivery delivery = delivery("FAILED", 2);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(subscriptionMapper.selectById(10L)).thenReturn(subscription());
        when(httpClient.post(any())).thenThrow(new IllegalStateException("timeout"));

        WebhookDeliveryPublishResponse response = deliveryService.publishPending(10);

        assertThat(response.getDead()).isEqualTo(1);
        ArgumentCaptor<WebhookDelivery> patchCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo("DEAD");
        assertThat(patchCaptor.getValue().getLastError()).contains("timeout");
    }

    private WebhookDelivery delivery(String status, int retryCount) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(1L);
        delivery.setSubscriptionId(10L);
        delivery.setAppId(20L);
        delivery.setEventType("COMPUTE_RECEIPT_CREATED");
        delivery.setPayload("{\"receiptNo\":\"R1\"}");
        delivery.setStatus(status);
        delivery.setRetryCount(retryCount);
        delivery.setIsDeleted(0);
        return delivery;
    }

    private WebhookSubscription subscription() {
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setId(10L);
        subscription.setAppId(20L);
        subscription.setEventType("COMPUTE_RECEIPT_CREATED");
        subscription.setCallbackUrl("https://merchant.example/webhook");
        subscription.setSecret("nxwh_secret");
        subscription.setStatus("ACTIVE");
        subscription.setIsDeleted(0);
        return subscription;
    }
}
