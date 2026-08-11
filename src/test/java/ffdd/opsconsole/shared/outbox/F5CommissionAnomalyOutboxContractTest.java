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
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class F5CommissionAnomalyOutboxContractTest {
    private static final String EVENT = "admin.commission_anomaly_config_changed";

    private final EventOutboxMapper mapper = Mockito.mock(EventOutboxMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventOutboxService service =
            new EventOutboxService(mapper, objectMapper, new OutboxProperties(),
                    org.mockito.Mockito.mock(ffdd.opsconsole.platform.application.A4RuntimePolicyService.class));

    @Test
    void revision303AcceptsAndPersistsTheCanonicalF5Payload() throws Exception {
        when(mapper.findActiveSchema(EVENT))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 303, true));
        when(mapper.listActiveProperties(EVENT)).thenReturn(properties());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish("ADMIN_COMMISSION", "F5-CFG-1", EVENT, payload());

        verify(mapper).insertEvent(
                eq(eventId), eq("ADMIN_COMMISSION"), eq("F5-CFG-1"), eq(EVENT),
                eq(EVENT), eq("phase_admin"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(303), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("before_commission_anomaly_sigma").decimalValue())
                .isEqualByComparingTo("3.0");
        assertThat(envelope.path("after_commission_anomaly_sigma").decimalValue())
                .isEqualByComparingTo("3.5");
        assertThat(envelope.path("before_layer_ratio_anomaly_pct").decimalValue())
                .isEqualByComparingTo("20");
        assertThat(envelope.path("after_layer_ratio_anomaly_pct").decimalValue())
                .isEqualByComparingTo("25");
        assertThat(envelope.path("operator").asText()).isEqualTo("f5-operator");
        assertThat(envelope.path("reason").asText()).isEqualTo("tighten F5 anomaly detection");
        assertThat(envelope.path("schema_revision").asInt()).isEqualTo(303);
    }

    @Test
    void revision303RejectsMissingRequiredProducerFieldBeforeInsert() {
        when(mapper.findActiveSchema(EVENT))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 303, true));
        when(mapper.listActiveProperties(EVENT)).thenReturn(properties());
        Map<String, Object> missingReason = new LinkedHashMap<>(payload());
        missingReason.remove("reason");

        assertThatThrownBy(() ->
                service.publish("ADMIN_COMMISSION", "F5-CFG-2", EVENT, missingReason))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void unknownFutureRevisionWithoutSameRevisionPropertiesFailsClosed() {
        when(mapper.findActiveSchema(EVENT))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 304, true));
        when(mapper.listActiveProperties(EVENT)).thenReturn(List.of());

        assertThatThrownBy(() ->
                service.publish("ADMIN_COMMISSION", "F5-CFG-304", EVENT, payload()))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    private List<EventOutboxMapper.SchemaPropertyGateRow> properties() {
        return List.of(
                required("before_commission_anomaly_sigma", "number"),
                required("after_commission_anomaly_sigma", "number"),
                required("before_layer_ratio_anomaly_pct", "number"),
                required("after_layer_ratio_anomaly_pct", "number"),
                required("operator", "string"),
                required("reason", "string"));
    }

    private EventOutboxMapper.SchemaPropertyGateRow required(String name, String type) {
        return new EventOutboxMapper.SchemaPropertyGateRow(name, type, true);
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("beforeCommissionAnomalySigma", new BigDecimal("3.0"));
        payload.put("afterCommissionAnomalySigma", new BigDecimal("3.5"));
        payload.put("beforeLayerRatioAnomalyPct", new BigDecimal("20"));
        payload.put("afterLayerRatioAnomalyPct", new BigDecimal("25"));
        payload.put("operator", "f5-operator");
        payload.put("reason", "tighten F5 anomaly detection");
        return payload;
    }
}
