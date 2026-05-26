package ffdd.openapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.openapi.mapper.OpenApiAppMapper;
import ffdd.openapi.mapper.OpenApiCallAuditMapper;
import ffdd.openapi.mapper.WebhookDeliveryMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiOpsStatsServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final OpenApiAppMapper appMapper = mock(OpenApiAppMapper.class);
    private final OpenApiCallAuditMapper auditMapper = mock(OpenApiCallAuditMapper.class);
    private final WebhookDeliveryMapper deliveryMapper = mock(WebhookDeliveryMapper.class);
    private final OpenApiOpsStatsService service =
            new OpenApiOpsStatsService(appMapper, auditMapper, deliveryMapper, CLOCK);

    @Test
    @SuppressWarnings("unchecked")
    void summarizesOpenApiKpisWithBoundedWindow() {
        when(appMapper.selectCount(any()))
                .thenReturn(4L)
                .thenReturn(3L)
                .thenReturn(1L);
        when(auditMapper.selectCount(any()))
                .thenReturn(100L)
                .thenReturn(92L)
                .thenReturn(8L);
        when(deliveryMapper.selectCount(any()))
                .thenReturn(30L)
                .thenReturn(5L)
                .thenReturn(20L)
                .thenReturn(3L)
                .thenReturn(2L);

        Map<String, Object> stats = service.summary(365);

        assertThat(stats)
                .containsEntry("service", "nexion-openapi-service")
                .containsEntry("days", 90);
        assertThat((Map<String, Object>) stats.get("apps"))
                .containsEntry("total", 4L)
                .containsEntry("active", 3L)
                .containsEntry("disabled", 1L);
        assertThat((Map<String, Object>) stats.get("calls"))
                .containsEntry("total", 100L)
                .containsEntry("success", 92L)
                .containsEntry("failed", 8L);
        assertThat((Map<String, Object>) stats.get("webhooks"))
                .containsEntry("total", 30L)
                .containsEntry("pending", 5L)
                .containsEntry("success", 20L)
                .containsEntry("failed", 3L)
                .containsEntry("dead", 2L);
    }
}
