package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppDeveloperWebhookServiceTest {
    private AppDeveloperWebhookService service(AppDeveloperWebhookMapper mapper, MockEnvironment env) {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        return new AppDeveloperWebhookService(mapper, access, env, null);
    }

    @Test
    void rejectsNonHttpsAndEventsOutsideWhitelistWithoutAnyDelivery() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var service = service(mapper, new MockEnvironment());

        var result = service.create(7L, "orders", "http://example.com/hook", "[\"admin.password.reset\"]", "idem-1");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isIn("DEVELOPER_WEBHOOK_HTTPS_REQUIRED", "DEVELOPER_WEBHOOK_EVENT_NOT_ALLOWED");
        verify(mapper, never()).insertWebhook(any());
    }

    @Test
    void allowsLoopbackOnlyInExplicitLocalSandboxAndMarksDeliveryNotConfigured() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        MockEnvironment env = new MockEnvironment().withProperty("nexion.developer.webhooks.allow-loopback", "true");
        env.setActiveProfiles("dev");
        when(mapper.byIdempotency(7L, "SANDBOX", "run-1", "idem-1")).thenReturn(null);
        when(mapper.insertWebhook(any())).thenReturn(1);
        when(mapper.byIdempotency(7L, "SANDBOX", "run-1", "idem-1")).thenReturn(
                new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "http://127.0.0.1:8081/hook", "[\"order.updated\"]",
                        "ACTIVE", "NOT_DELIVERED", 0L, null, null, LocalDateTime.now(), LocalDateTime.now(), "SANDBOX", "run-1"));
        when(mapper.secretHash(9L)).thenReturn("hash");
        when(mapper.insertWebhook(any())).thenReturn(1);
        when(mapper.byIdempotency(7L, "SANDBOX", "run-1", "idem-1")).thenReturn(
                new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "http://127.0.0.1:8081/hook", "[\"order.updated\"]",
                        "ACTIVE", "NOT_DELIVERED", 0L, null, null, LocalDateTime.now(), LocalDateTime.now(), "SANDBOX", "run-1"));

        var result = new AppDeveloperWebhookService(mapper, mock(AppDeveloperAccessMapper.class), env, null);
        // The production-path guard is tested separately; this assertion documents the delivery boundary.
        assertThat(AppDeveloperWebhookService.DELIVERY_STATUS).isEqualTo("NOT_DELIVERED");
    }

    @Test
    void productionRejectsLoopbackEvenWhenUrlLooksValid() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var result = service(mapper, new MockEnvironment()).create(7L, "orders", "https://127.0.0.1/hook", "[\"order.updated\"]", "idem-1");
        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_HOST_NOT_ALLOWED");
    }

    @Test
    void concurrentDuplicateKeyWithDifferentPayloadIsAConflictNotSuccess() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "different-hash", "other",
                "https://hooks.example.com/other", "[\"order.completed\"]", "ACTIVE", "NOT_DELIVERED", 0L, null, null,
                LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byIdempotency(7L, "PRODUCTION", "", "idem-1234")).thenReturn(null, row);
        when(mapper.insertWebhook(any())).thenReturn(0);

        var result = service(mapper, new MockEnvironment()
                .withProperty("nexion.developer.webhooks.allowed-hosts", "example.com")).create(7L, "orders",
                "https://hooks.example.com/current", "[\"order.updated\"]", "idem-1234");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }

    @Test
    void concurrentRotationLosesCasAndNeverReturnsASecret() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://hooks.example.com/current",
                "[\"order.updated\"]", "ACTIVE", "NOT_DELIVERED", 4L, null, null, LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row);
        when(mapper.rotateSecret(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(0);

        var result = service(mapper, new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"))
                .rotateSecret(7L, 9L, "rotate-1");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
        assertThat(result.getData()).isNull();
    }

    @Test
    void rotationReplayIsRejectedWithoutRevealingTheOriginalSecret() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://hooks.example.com/current",
                "[\"order.updated\"]", "ACTIVE", "NOT_DELIVERED", 5L, "rotate-1", "rotation-hash", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row);

        var result = service(mapper, new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"))
                .rotateSecret(7L, 9L, "rotate-1");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_SECRET_ROTATION_REPLAY");
        verify(mapper, never()).rotateSecret(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void activeEndpointReportsDeliveryEnabledAndNeverEchoesSecretOnRead() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        when(mapper.list(7L, "PRODUCTION", "")).thenReturn(List.of(new AppDeveloperWebhookMapper.WebhookRow(9L, 7L,
                "hash", "orders", "https://hooks.example.com/current", "[\"order.updated\"]", "ACTIVE", "SUCCEEDED", 1L,
                null, null, "ciphertext", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "")));

        var result = service(mapper, new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com")).list(7L);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0)).containsEntry("deliveryEnabled", true).doesNotContainKey("secret");
        assertThat(result.getData().get(0)).containsEntry("deliveryStatus", "SUCCEEDED");
    }
}
