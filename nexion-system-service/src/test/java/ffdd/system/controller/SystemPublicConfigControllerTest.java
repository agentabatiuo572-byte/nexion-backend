package ffdd.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.api.ApiResult;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.service.SystemConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemPublicConfigControllerTest {
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final SystemPublicConfigController controller =
            new SystemPublicConfigController(systemConfigService, new ObjectMapper());

    @Test
    void exposesGrowthTrialConfigViaSystemFallbackRoute() {
        when(systemConfigService.listPublicByGroup("growth")).thenReturn(List.of(
                new ConfigItemResponse(160L, "growth.trial.enabled", "true", "BOOLEAN", "growth", "PUBLIC", "Trial enabled.", 1, null, null),
                new ConfigItemResponse(161L, "growth.trial.device_name", "NexionBox S1", "STRING", "growth", "PUBLIC", "Trial device.", 1, null, null),
                new ConfigItemResponse(163L, "growth.trial.daily_usdt", "38.50", "NUMBER", "growth", "PUBLIC", "Daily shadow USD.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.growth();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("trial.enabled", true)
                .containsEntry("trial.device_name", "NexionBox S1")
                .containsEntry("trial.daily_usdt", new java.math.BigDecimal("38.50"));
    }
}
