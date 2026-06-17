package ffdd.opsconsole.emergency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsKillSwitchControllerTest {
    private final OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
    private final OpsKillSwitchController controller = new OpsKillSwitchController(killSwitchService);

    @Test
    void matrixDelegatesToService() {
        when(killSwitchService.matrix()).thenReturn(ApiResult.ok(Map.of("activeGateCount", 5)));

        assertThat(controller.matrix().getData()).containsEntry("activeGateCount", 5);

        verify(killSwitchService).matrix();
    }

    @Test
    void toggleDelegatesWithIdempotencyHeader() {
        KillSwitchToggleRequest request = new KillSwitchToggleRequest("disabled", "incident", "risk-lead");
        when(killSwitchService.toggle("withdraw", "idem-j1", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.toggle("withdraw", "idem-j1", request).getData()).containsEntry("ok", true);

        verify(killSwitchService).toggle("withdraw", "idem-j1", request);
    }
}
