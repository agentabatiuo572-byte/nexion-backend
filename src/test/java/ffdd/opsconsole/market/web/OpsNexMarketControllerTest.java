package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsNexMarketControllerTest {
    private final OpsNexMarketService marketService = mock(OpsNexMarketService.class);
    private final OpsNexMarketController controller = new OpsNexMarketController(marketService);

    @Test
    void curveDelegatesToService() {
        when(marketService.overview()).thenReturn(ApiResult.ok(Map.of("frames", List.of())));

        assertThat(controller.curve().getData()).containsKey("frames");

        verify(marketService).overview();
    }

    @Test
    void updateCurveDelegatesWithIdempotencyHeader() {
        NexMarketCurveUpdateRequest request = new NexMarketCurveUpdateRequest(List.of(), "reason", "superadmin");
        when(marketService.updateWeeklyCurve("idem-g3", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateCurve("idem-g3", request).getCode()).isZero();

        verify(marketService).updateWeeklyCurve("idem-g3", request);
    }

    @Test
    void advanceDelegatesWithIdempotencyHeader() {
        NexMarketAdvanceRequest request = new NexMarketAdvanceRequest("daily", "system");
        when(marketService.advanceCurrentFrame("idem-advance", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.advance("idem-advance", request).getData()).containsEntry("ok", true);

        verify(marketService).advanceCurrentFrame("idem-advance", request);
    }
}
