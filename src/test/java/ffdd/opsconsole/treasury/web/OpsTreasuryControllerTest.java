package ffdd.opsconsole.treasury.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.dto.TreasuryAlertAckRequest;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryForecastConfigRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsTreasuryControllerTest {
    private final OpsTreasuryService treasuryService = mock(OpsTreasuryService.class);
    private final OpsTreasuryController controller = new OpsTreasuryController(treasuryService);

    @Test
    void overviewDelegatesToTreasuryService() {
        when(treasuryService.overview(7)).thenReturn(ApiResult.ok(Map.of("domain", "B")));

        ApiResult<Map<String, Object>> result = controller.overview(7);

        assertThat(result.getData()).containsEntry("domain", "B");
        verify(treasuryService).overview(7);
    }

    @Test
    void bDomainDashboardDelegatesToTreasuryService() {
        when(treasuryService.bDomainDashboard()).thenReturn(ApiResult.ok(Map.of("domain", "B")));

        ApiResult<Map<String, Object>> result = controller.bDomainDashboard();

        assertThat(result.getData()).containsEntry("domain", "B");
        verify(treasuryService).bDomainDashboard();
    }

    @Test
    void bDomainAlertAckDelegatesWithIdempotencyHeader() {
        TreasuryAlertAckRequest request = new TreasuryAlertAckRequest("handled", "superadmin");
        when(treasuryService.acknowledgeBDomainAlert("coverage-redline", "idem-alert", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        ApiResult<Map<String, Object>> result =
                controller.acknowledgeBDomainAlert("coverage-redline", "idem-alert", request);

        assertThat(result.getData()).containsEntry("ok", true);
        verify(treasuryService).acknowledgeBDomainAlert("coverage-redline", "idem-alert", request);
    }

    @Test
    void injectionDelegatesWithIdempotencyHeader() {
        TreasuryInjectionRequest request =
                new TreasuryInjectionRequest(new BigDecimal("100"), "V-1", "top-up", "superadmin");
        when(treasuryService.createInjection("idem-1", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.createInjection("idem-1", request).getData()).containsEntry("ok", true);

        verify(treasuryService).createInjection("idem-1", request);
    }

    @Test
    void scopeDelegatesWithIdempotencyHeader() {
        TreasuryScopeRequest request = new TreasuryScopeRequest("active liabilities", "scope change", "superadmin");
        when(treasuryService.updateScope("idem-2", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateScope("idem-2", request).getData()).containsEntry("ok", true);

        verify(treasuryService).updateScope("idem-2", request);
    }

    @Test
    void thresholdDelegatesWithIdempotencyHeader() {
        TreasuryThresholdRequest request = new TreasuryThresholdRequest(new BigDecimal("90"), null, null, "policy", "superadmin");
        when(treasuryService.updateThresholds("idem-3", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateThresholds("idem-3", request).getData()).containsEntry("ok", true);

        verify(treasuryService).updateThresholds("idem-3", request);
    }

    @Test
    void d3CanonicalEndpointsDelegateToTreasuryService() {
        TreasuryForecastConfigRequest request = new TreasuryForecastConfigRequest(
                null, null, "30d", true, false, "LINEAR", false,
                1L, "调整未来三十天预测配置", "finance-lead");
        when(treasuryService.reserve()).thenReturn(ApiResult.ok(Map.of("reserveTotalUsdt", BigDecimal.TEN)));
        when(treasuryService.coverage()).thenReturn(ApiResult.ok(Map.of("coverageRatio", new BigDecimal("120"))));
        when(treasuryService.liabilities(true)).thenReturn(ApiResult.ok(Map.of("hardLiabilityCategoryCount", 8)));
        when(treasuryService.maturityForecast("30d")).thenReturn(ApiResult.ok(Map.of("window", "30d")));
        when(treasuryService.netExposure("90d")).thenReturn(ApiResult.ok(Map.of("window", "90d")));
        when(treasuryService.forecastConfig()).thenReturn(ApiResult.ok(Map.of("forecastWindow", "30d")));
        when(treasuryService.updateForecastConfig("idem-d3-config", request))
                .thenReturn(ApiResult.ok(Map.of("version", 2L)));

        assertThat(controller.reserve().getData()).containsEntry("reserveTotalUsdt", BigDecimal.TEN);
        assertThat(controller.coverage().getData()).containsEntry("coverageRatio", new BigDecimal("120"));
        assertThat(controller.liabilities(true).getData()).containsEntry("hardLiabilityCategoryCount", 8);
        assertThat(controller.maturityForecast("30d").getData()).containsEntry("window", "30d");
        assertThat(controller.netExposure("90d").getData()).containsEntry("window", "90d");
        assertThat(controller.forecastConfig().getData()).containsEntry("forecastWindow", "30d");
        assertThat(controller.updateForecastConfig("idem-d3-config", request).getData()).containsEntry("version", 2L);

        verify(treasuryService).reserve();
        verify(treasuryService).coverage();
        verify(treasuryService).liabilities(true);
        verify(treasuryService).maturityForecast("30d");
        verify(treasuryService).netExposure("90d");
        verify(treasuryService).forecastConfig();
        verify(treasuryService).updateForecastConfig("idem-d3-config", request);
    }
}
