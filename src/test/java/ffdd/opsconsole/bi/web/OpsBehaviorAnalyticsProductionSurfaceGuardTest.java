package ffdd.opsconsole.bi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OpsBehaviorAnalyticsProductionSurfaceGuardTest {
    @Test
    void acceptanceAdminCannotBypassTheSandboxObserverThroughProductionReadOrExportRoutes() {
        BehaviorAnalyticsService service = mock(BehaviorAnalyticsService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        OpsBehaviorAnalyticsController controller = new OpsBehaviorAnalyticsController(service, environment);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                controller.behavior("7d", "ALL", "ALL", "ALL", "pv")))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_PRODUCTION_SURFACE_FORBIDDEN");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                controller.export("7d", "ALL", "ALL", "ALL", "pv")))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_PRODUCTION_SURFACE_FORBIDDEN");
        verifyNoInteractions(service);
    }
}
