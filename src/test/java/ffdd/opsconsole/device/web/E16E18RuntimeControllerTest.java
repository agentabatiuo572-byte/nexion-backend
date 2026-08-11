package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class E16E18RuntimeControllerTest {
    @Test
    void e18RoutesUsingTheRuntimeServiceBoundary() {
        OpsDeviceService service = mock(OpsDeviceService.class);
        ApiResult<Map<String, Object>> response = ApiResult.ok(Map.of(
                "routable", true,
                "selectedTask", Map.of("taskClass", "IG")));
        when(service.routeE2Task(24)).thenReturn(response);

        assertThat(new AppE2ConfigController(service).routeTask(24)).isSameAs(response);
    }

    @Test
    void e16ExposesServerFleetObservability() {
        OpsDeviceService service = mock(OpsDeviceService.class);
        ApiResult<Map<String, Object>> response = ApiResult.ok(Map.of(
                "telemetry", Map.of("activeTasks", 3),
                "activity", java.util.List.of()));
        when(service.e5Observability()).thenReturn(response);

        assertThat(new OpsDeviceController(service).observability()).isSameAs(response);
    }
}
