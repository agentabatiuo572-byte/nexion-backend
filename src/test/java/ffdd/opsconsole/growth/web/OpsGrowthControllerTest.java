package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsGrowthControllerTest {
    private final OpsGrowthService growthService = mock(OpsGrowthService.class);
    private final OpsGrowthController controller = new OpsGrowthController(growthService);

    @Test
    void phaseOverviewDelegatesToService() {
        when(growthService.phases()).thenReturn(ApiResult.ok(Map.of("dialCount", 8)));

        assertThat(controller.phases().getData()).containsEntry("dialCount", 8);

        verify(growthService).phases();
    }

    @Test
    void updateCheckInDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("baseRewardNex", "0.2", "tighten", "superadmin");
        when(growthService.updateCheckIn("idem-h5", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateCheckIn("idem-h5", request).getData()).containsEntry("ok", true);

        verify(growthService).updateCheckIn("idem-h5", request);
    }

    @Test
    void updateWithdrawGateDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("holdDays", "14", "tighten", "superadmin");
        when(growthService.updateWithdrawGate("idem-h1", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateWithdrawGate("idem-h1", request).getData()).containsEntry("ok", true);

        verify(growthService).updateWithdrawGate("idem-h1", request);
    }
}
