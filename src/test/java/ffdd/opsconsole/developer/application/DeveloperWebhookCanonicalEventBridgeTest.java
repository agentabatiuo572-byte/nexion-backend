package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DeveloperWebhookCanonicalEventBridgeTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsCanonicalOrderEventAndStripsSensitivePayloadFieldsBeforeEnqueue() throws Exception {
        var delivery = mock(DeveloperWebhookDeliveryService.class);
        var access = mock(AppDeveloperAccessMapper.class);
        when(access.userSandbox(7L)).thenReturn(0);
        var bridge = new DeveloperWebhookCanonicalEventBridge(delivery, access, new MockEnvironment(), objectMapper);

        var message = message("evt-order-1", "ORDER", "checkout.completed",
                "{\"user_id\":7,\"order_id\":\"o-1\",\"email\":\"secret@example.com\",\"api_token\":\"do-not-send\",\"total\":12.5}");
        when(delivery.enqueue(eq(7L), eq("PRODUCTION"), eq(""), eq("evt-order-1"), eq("order.completed"), anyString(), isNull()))
                .thenReturn(1);

        assertThat(bridge.bridge(message)).isEqualTo(1);
        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).enqueue(eq(7L), eq("PRODUCTION"), eq(""), eq("evt-order-1"), eq("order.completed"), payloadCaptor.capture(), isNull());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.get("user_id").asLong()).isEqualTo(7L);
        assertThat(payload.get("order_id").asText()).isEqualTo("o-1");
        assertThat(payload.has("email")).isFalse();
        assertThat(payload.has("api_token")).isFalse();
        assertThat(payload.get("total").asDouble()).isEqualTo(12.5D);
    }

    @Test
    void rejectsUnknownEventsAndDoesNotCreateDelivery() {
        var delivery = mock(DeveloperWebhookDeliveryService.class);
        var access = mock(AppDeveloperAccessMapper.class);
        var bridge = new DeveloperWebhookCanonicalEventBridge(delivery, access, new MockEnvironment(), objectMapper);

        assertThat(bridge.bridge(message("evt-unknown", "ORDER", "order.deleted", "{\"user_id\":7}"))).isZero();
        verifyNoInteractions(delivery, access);
    }

    @Test
    void requiresMatchingSandboxRunFenceAndPreservesSandboxScope() {
        var delivery = mock(DeveloperWebhookDeliveryService.class);
        var access = mock(AppDeveloperAccessMapper.class);
        when(access.userSandbox(7L)).thenReturn(1);
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-1");
        environment.setActiveProfiles("dev");
        var bridge = new DeveloperWebhookCanonicalEventBridge(delivery, access, environment, objectMapper);

        assertThat(bridge.bridge(message("evt-stale", "ORDER", "order.updated",
                "{\"user_id\":7,\"source_environment\":\"SANDBOX\",\"run_id\":\"run-old\"}"))).isZero();
        assertThat(bridge.bridge(message("evt-missing-fence", "ORDER", "order.updated",
                "{\"user_id\":7}"))).isZero();
        verifyNoInteractions(delivery);

        var current = message("evt-current", "ORDER", "order.updated",
                "{\"user_id\":7,\"source_environment\":\"SANDBOX\",\"run_id\":\"run-1\"}");
        when(delivery.enqueue(eq(7L), eq("SANDBOX"), eq("run-1"), eq("evt-current"), eq("order.updated"), anyString(), isNull()))
                .thenReturn(1);
        assertThat(bridge.bridge(current)).isEqualTo(1);
    }

    @Test
    void eventMappingCoversPublicAllowlistWithoutMappingUnknownAdminFacts() {
        assertThat(DeveloperWebhookCanonicalEventBridge.mapEvent("checkout.started", "ORDER")).contains("order.updated");
        assertThat(DeveloperWebhookCanonicalEventBridge.mapEvent("task.completed", "COMPUTE_TASK")).contains("compute.job.completed");
        assertThat(DeveloperWebhookCanonicalEventBridge.mapEvent("task.failed", "COMPUTE_TASK")).contains("compute.job.failed");
        assertThat(DeveloperWebhookCanonicalEventBridge.mapEvent("earnings.credited", "COMPUTE_TASK")).contains("earnings.updated");
        assertThat(DeveloperWebhookCanonicalEventBridge.mapEvent("market.curve_advanced", "NEX_MARKET")).contains("market.updated");
        assertThat(DeveloperWebhookCanonicalEventBridge.mapEvent("admin.password_reset_requested", "USER")).isEmpty();
    }

    private EventOutboxMessage message(String id, String aggregateType, String eventType, String payload) {
        var message = new EventOutboxMessage();
        message.setEventId(id);
        message.setAggregateType(aggregateType);
        message.setAggregateId("aggregate-1");
        message.setEventType(eventType);
        message.setEventName(eventType);
        message.setPayload(payload);
        return message;
    }
}
