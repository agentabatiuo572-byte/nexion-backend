package ffdd.opsconsole.device.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MybatisDeviceOpsRepositoryObservabilityTest {
    @Test
    void returnsMeasuredRuntimeAndDurableActivityWithoutInventingMissingMetrics() {
        DeviceOpsMapper mapper = mock(DeviceOpsMapper.class);
        LocalDateTime capturedAt = LocalDateTime.of(2026, 8, 10, 12, 0);
        when(mapper.e5FleetObservabilityMetrics()).thenReturn(new DeviceOpsMapper.FleetObservabilityMetrics(
                4L, 2L, 9L, new BigDecimal("61.5"), new BigDecimal("233.1"), capturedAt));
        when(mapper.countE5ReconnectEvents24h()).thenReturn(7L);
        when(mapper.e5Activity24h()).thenReturn(List.of(new DeviceOpsMapper.E5ActivityRow(
                "admin.device_activated", "E5_DEVICE", "42", capturedAt)));

        Map<String, Object> result = new MybatisDeviceOpsRepository(mapper, mock(OpsReadTimeSeedPolicy.class))
                .e5Observability();
        @SuppressWarnings("unchecked")
        Map<String, Object> telemetry = (Map<String, Object>) result.get("telemetry");

        assertThat(telemetry).containsEntry("heartbeatLost1h", 4L)
                .containsEntry("reconnectEvents24h", 7L)
                .containsEntry("activeTasks", 9L)
                .containsEntry("avgCpuUsagePct", null)
                .containsEntry("dispatchLatencyP95Ms", null);
        assertThat((List<?>) result.get("activity")).hasSize(1);
        assertThat(result.get("sources")).isEqualTo(List.of("nx_user_device_runtime", "nx_event_outbox"));
    }
}
