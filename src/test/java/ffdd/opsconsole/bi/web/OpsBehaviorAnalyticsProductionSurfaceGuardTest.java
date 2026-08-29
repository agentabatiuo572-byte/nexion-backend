package ffdd.opsconsole.bi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OpsBehaviorAnalyticsProductionSurfaceGuardTest {
    @Test
    void developmentUsesTheCanonicalBehaviorReadAndExportSurface() {
        BehaviorAnalyticsService service = mock(BehaviorAnalyticsService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        OpsBehaviorAnalyticsController controller = new OpsBehaviorAnalyticsController(service, environment);
        when(service.behavior("7d", "ALL", "ALL", "ALL", "pv"))
                .thenReturn(ApiResult.ok(Map.of("sourceEnvironment", "PRODUCTION")));
        when(service.exportBehavior("7d", "ALL", "ALL", "ALL", "pv")).thenReturn(new byte[] {1});

        assertThat(controller.behavior("7d", "ALL", "ALL", "ALL", "pv").getData())
                .containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat(controller.export("7d", "ALL", "ALL", "ALL", "pv").getBody()).containsExactly(1);
        verify(service).behavior("7d", "ALL", "ALL", "ALL", "pv");
        verify(service).exportBehavior("7d", "ALL", "ALL", "ALL", "pv");
    }
}
