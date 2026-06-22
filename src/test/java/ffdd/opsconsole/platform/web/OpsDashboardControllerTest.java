package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.OpsDashboardService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsDashboardControllerTest {
    private final OpsDashboardService dashboardService = mock(OpsDashboardService.class);
    private final OpsDashboardController controller = new OpsDashboardController(dashboardService);

    @Test
    void summaryDelegatesToDashboardService() {
        when(dashboardService.summary()).thenReturn(ApiResult.ok(Map.of("service", "ops-dashboard")));

        ApiResult<Map<String, Object>> result = controller.summary();

        assertThat(result.getData()).containsEntry("service", "ops-dashboard");
        verify(dashboardService).summary();
    }
}
