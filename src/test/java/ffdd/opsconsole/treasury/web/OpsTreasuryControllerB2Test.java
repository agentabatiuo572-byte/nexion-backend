package ffdd.opsconsole.treasury.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.dto.TreasuryForecastConfigRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsTreasuryControllerB2Test {
    private final OpsTreasuryService service = mock(OpsTreasuryService.class);
    private final OpsTreasuryController controller = new OpsTreasuryController(service);

    @Test
    void b2ReadAuthorityCanReachOnlyTheCanonicalFactsUsedByTheDashboard() {
        for (String methodName : List.of("reserve", "liabilities", "maturityForecast", "forecastConfig")) {
            String expression = authority(methodName);
            assertThat(expression)
                    .contains("finance_d3_read", "overview_b2_read")
                    .doesNotContain("overview_b2_write", "overview_b2_export");
        }
        assertThat(authority("netExposure")).doesNotContain("overview_b2_read");
        assertThat(authority("reconciliationExport")).doesNotContain("overview_b2_export");
    }

    @Test
    void b2WriteAndExportKeepTheirDedicatedAuthoritiesAlongsideD3() {
        assertThat(authority("updateForecastConfig"))
                .contains("finance_d3_write", "overview_b2_write")
                .doesNotContain("overview_b2_read");
        assertThat(authority("liabilitiesExport"))
                .contains("finance_d3_export", "overview_b2_export")
                .doesNotContain("overview_b2_read");
    }

    @Test
    void b2ReadEndpointsDelegateToTheExistingD3CanonicalService() {
        when(service.reserve()).thenReturn(ApiResult.ok(Map.of("kind", "reserve")));
        when(service.liabilities(true)).thenReturn(ApiResult.ok(Map.of("kind", "liabilities")));
        when(service.maturityForecast("30d")).thenReturn(ApiResult.ok(Map.of("window", "30d")));
        when(service.forecastConfig()).thenReturn(ApiResult.ok(Map.of("version", 1)));

        assertThat(controller.reserve().getData()).containsEntry("kind", "reserve");
        assertThat(controller.liabilities(true).getData()).containsEntry("kind", "liabilities");
        assertThat(controller.maturityForecast("30d").getData()).containsEntry("window", "30d");
        assertThat(controller.forecastConfig().getData()).containsEntry("version", 1);

        verify(service).reserve();
        verify(service).liabilities(true);
        verify(service).maturityForecast("30d");
        verify(service).forecastConfig();
    }

    @Test
    void b2ConfigAndExportPassThroughConcurrencyPayloadAndCanonicalCsv() {
        TreasuryForecastConfigRequest request = mock(TreasuryForecastConfigRequest.class);
        when(service.updateForecastConfig("idem-b2", request)).thenReturn(ApiResult.ok(Map.of("version", 2)));
        when(service.liabilitiesCsv()).thenReturn("category,amount\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(controller.updateForecastConfig("idem-b2", request).getData()).containsEntry("version", 2);
        assertThat(controller.liabilitiesExport().getBody()).isEqualTo("category,amount\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        verify(service).updateForecastConfig("idem-b2", request);
        verify(service).liabilitiesCsv();
    }

    private static String authority(String methodName) {
        Method method = java.util.Arrays.stream(OpsTreasuryController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
