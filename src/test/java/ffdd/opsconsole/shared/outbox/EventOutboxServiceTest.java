package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class EventOutboxServiceTest {
    private final EventOutboxMapper mapper = Mockito.mock(EventOutboxMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventOutboxService service = new EventOutboxService(mapper, objectMapper, new OutboxProperties());

    @Test
    void registeredAnalyticsEventIsPersistedWithCanonicalA4Envelope() throws Exception {
        when(mapper.findActiveSchema("risk.multi_account_flagged"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("risk", 5, true));
        when(mapper.listActiveProperties("risk.multi_account_flagged"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("actor_id", "id", false)));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish(
                "RISK_CLUSTER", "cluster-7", "RISK_MULTI_ACCOUNT_FLAGGED",
                Map.of("actor_id", "user-9", "phase", "P6", "cohort", "spoofed", "accountAgeMonths", -1));

        verify(mapper).insertEvent(
                eq(eventId), eq("RISK_CLUSTER"), eq("cluster-7"), eq("RISK_MULTI_ACCOUNT_FLAGGED"),
                eq("risk.multi_account_flagged"), eq("risk"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(5), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("event_id").asText()).isEqualTo(eventId);
        assertThat(envelope.path("event_name").asText()).isEqualTo("risk.multi_account_flagged");
        assertThat(envelope.path("actor_id").asText()).isEqualTo("user-9");
        assertThat(envelope.path("ts").asLong()).isPositive();
        assertThat(envelope.path("phase").asText()).isEqualTo("SYSTEM");
        assertThat(envelope.path("account_age_months").asInt()).isZero();
        assertThat(envelope.path("cohort").asText()).matches("\\d{4}-W\\d{2}");
        assertThat(envelope.has("user_id")).isTrue();
        assertThat(envelope.has("anon_id")).isTrue();
        assertThat(envelope.has("session_id")).isTrue();
        assertThat(envelope.has("ref")).isTrue();
        assertThat(envelope.has("source")).isTrue();
        assertThat(envelope.path("platform").asText()).isEqualTo("server");
        assertThat(envelope.path("is_server_authoritative").asBoolean()).isTrue();
        assertThat(envelope.path("schema_revision").asInt()).isEqualTo(5);
    }

    @Test
    void canonicalEventNameUsesRegistryWithoutHardcodedEventTypeMapping() {
        when(mapper.findActiveSchema("checkout.completed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("conversion", 8, true));
        when(mapper.listActiveProperties("checkout.completed"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("order_id", "id", true)));

        String eventId = service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("orderId", "order-7"));

        verify(mapper).insertEvent(
                eq(eventId), eq("ORDER"), eq("order-7"), eq("checkout.completed"),
                eq("checkout.completed"), eq("conversion"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(8), eq(true), eq(true), anyString());
    }

    @Test
    void legacyLearningCompletionDeliveryTypeUsesRegisteredLearnAnalyticsSchema() throws Exception {
        when(mapper.findActiveSchema("learn.course_completed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("learn", 116, true));
        when(mapper.listActiveProperties("learn.course_completed")).thenReturn(List.of(
                required("course_id", "id"), required("course_version", "id"),
                required("nex_reward", "number")));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish(
                "LEARNING", "42:test-course:v2", "LEARNING_COURSE_COMPLETED",
                Map.of("user_id", 42L, "course_id", "test-course", "course_version", "v2",
                        "nex_reward", new java.math.BigDecimal("20.000000")));

        verify(mapper).insertEvent(
                eq(eventId), eq("LEARNING"), eq("42:test-course:v2"), eq("LEARNING_COURSE_COMPLETED"),
                eq("learn.course_completed"), eq("learn"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(116), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("event_name").asText()).isEqualTo("learn.course_completed");
        assertThat(envelope.path("user_id").asLong()).isEqualTo(42L);
        assertThat(envelope.path("nex_reward").decimalValue()).isEqualByComparingTo("20.000000");
    }

    @Test
    void allC2HighRiskEventsSatisfyTheirRegisteredCanonicalPayloadContracts() {
        when(mapper.findActiveSchema("admin.user_frozen"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 25, true));
        when(mapper.findActiveSchema("admin.user_unfrozen"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 26, true));
        when(mapper.findActiveSchema("admin.user_impersonation_started"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 27, true));
        when(mapper.findActiveSchema("admin.user_impersonation_ended"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 28, true));
        List<EventOutboxMapper.SchemaPropertyGateRow> accountProperties = List.of(
                required("target_user_id", "id"), required("operator", "id"),
                required("reason", "string"), required("occurred_at", "timestamp"));
        when(mapper.listActiveProperties("admin.user_frozen")).thenReturn(accountProperties);
        when(mapper.listActiveProperties("admin.user_unfrozen")).thenReturn(accountProperties);
        when(mapper.listActiveProperties("admin.user_impersonation_started")).thenReturn(List.of(
                required("target_user_id", "id"), required("operator", "id"),
                required("reason", "string"), required("ttl_minutes", "number"),
                required("session_start", "timestamp"), required("occurred_at", "timestamp")));
        when(mapper.listActiveProperties("admin.user_impersonation_ended")).thenReturn(List.of(
                required("target_user_id", "id"), required("operator", "id"),
                required("reason", "string"), required("ttl_minutes", "number"),
                required("session_start", "timestamp"), required("session_end", "timestamp"),
                required("duration_sec", "number"), required("end_type", "enum"),
                required("occurred_at", "timestamp")));
        String now = "2026-07-18T12:00:00";

        service.publish("USER", "52", "admin.user_frozen", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "risk containment", "occurredAt", now));
        service.publish("USER", "52", "admin.user_unfrozen", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "risk cleared", "occurredAt", now));
        service.publish("USER_IMPERSONATION", "IMP-52", "admin.user_impersonation_started", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "support investigation", "ttlMinutes", 15,
                "sessionStart", now, "occurredAt", now));
        service.publish("USER_IMPERSONATION", "IMP-52", "admin.user_impersonation_ended", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "investigation complete", "ttlMinutes", 15,
                "sessionStart", now, "sessionEnd", "2026-07-18T12:05:00",
                "durationSec", 300L, "endType", "TERMINATED", "occurredAt", "2026-07-18T12:05:00"));

        verify(mapper, org.mockito.Mockito.times(4)).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), eq("phase_admin"),
                eq("SYSTEM"), eq(0), anyString(), eq(true), anyInt(), eq(true), eq(true), anyString());
    }

    @Test
    void userEventUsesTrustedServerAttributionAndOverridesSpoofedEnvelopeFields() throws Exception {
        when(mapper.findActiveSchema("checkout.started"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("conversion", 11, true));
        when(mapper.listActiveProperties("checkout.started"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("order_id", "id", true)));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publishUserEvent(
                "ORDER", "order-9", "checkout.started", 42L, "P4", 7, "2025-W52",
                Map.of("orderId", "order-9", "userId", 999L, "phase", "P1",
                        "accountAgeMonths", 99, "cohort", "spoofed"));

        verify(mapper).insertEvent(
                eq(eventId), eq("ORDER"), eq("order-9"), eq("checkout.started"),
                eq("checkout.started"), eq("conversion"), eq("P4"), eq(7), eq("2025-W52"),
                eq(true), eq(11), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("user_id").asLong()).isEqualTo(42L);
        assertThat(envelope.has("userId")).isFalse();
        assertThat(envelope.path("phase").asText()).isEqualTo("P4");
        assertThat(envelope.path("account_age_months").asInt()).isEqualTo(7);
        assertThat(envelope.has("accountAgeMonths")).isFalse();
        assertThat(envelope.path("cohort").asText()).isEqualTo("2025-W52");
    }

    @Test
    void analyticsPayloadRejectsUnregisteredPropertiesRawPiiAndTypeMismatch() {
        when(mapper.findActiveSchema("checkout.completed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("conversion", 8, true));
        when(mapper.listActiveProperties("checkout.completed"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("order_id", "id", true)));

        assertThatThrownBy(() -> service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("phone", "123456789")))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_EVENT_PAYLOAD_PII_REJECTED");
        assertThatThrownBy(() -> service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("unknownField", "value")))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        assertThatThrownBy(() -> service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("orderId", Map.of("nested", true))))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void unregisteredAnalyticsEventIsRejectedBeforeOutboxInsert() {
        when(mapper.findActiveSchema("admin.killswitch_toggled")).thenReturn(null);

        assertThatThrownBy(() -> service.publish(
                "KILL_SWITCH", "geo-block", "ADMIN_KILLSWITCH_TOGGLED", Map.of("enabled", true)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_NOT_REGISTERED");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void governedE1ProductEventUsesRegisteredSchemaAndCanonicalEnvelope() throws Exception {
        when(mapper.findActiveSchema("admin.product_listed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 40, true));
        when(mapper.listActiveProperties("admin.product_listed")).thenReturn(List.of(
                required("sku_key", "id"),
                required("before_status", "enum"),
                required("after_status", "enum"),
                required("operator", "id"),
                required("reason", "string")));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish("DEVICE_SKU", "sku-1", "admin.product_listed", Map.of(
                "sku_key", "sku-1",
                "before_status", "pending",
                "after_status", "on",
                "operator", "superadmin",
                "reason", "Publish eligible catalogue item"));

        verify(mapper).insertEvent(
                eq(eventId), eq("DEVICE_SKU"), eq("sku-1"), eq("admin.product_listed"),
                eq("admin.product_listed"), eq("phase_admin"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(40), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("event_name").asText()).isEqualTo("admin.product_listed");
        assertThat(envelope.path("sku_key").asText()).isEqualTo("sku-1");
        assertThat(envelope.path("schema_revision").asInt()).isEqualTo(40);
        assertThat(envelope.path("is_server_authoritative").asBoolean()).isTrue();
    }

    @Test
    void internalDeviceMessagesRemainOperationalWithoutPollutingA4Analytics() throws Exception {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish(
                "JANUS_DEVICE", "sid-1", "JANUS_DEVICE_STATUS_REQUESTED", Map.of("sid", "sid-1"));

        verify(mapper, never()).findActiveSchema(anyString());
        verify(mapper).insertEvent(
                eq(eventId), eq("JANUS_DEVICE"), eq("sid-1"), eq("JANUS_DEVICE_STATUS_REQUESTED"),
                eq("device.janus_device_status_requested"), eq("internal"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(null), eq(false), eq(false), payloadCaptor.capture());
        assertThat(objectMapper.readTree(payloadCaptor.getValue()).has("schema_revision")).isFalse();
    }

    private EventOutboxMapper.SchemaPropertyGateRow required(String name, String type) {
        return new EventOutboxMapper.SchemaPropertyGateRow(name, type, true);
    }
}
