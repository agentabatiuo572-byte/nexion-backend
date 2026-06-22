package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
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

    @Test
    void updateControlDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("D3", "pin demo", "superadmin");
        when(marketService.updateControl("idem-control", "pin", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateControl("idem-control", "pin", request).getCode()).isZero();

        verify(marketService).updateControl("idem-control", "pin", request);
    }

    @Test
    void updateOverrideDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("0.166", "manual override", "superadmin");
        when(marketService.updateOverride("idem-override", "currentPrice", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateOverride("idem-override", "currentPrice", request).getCode()).isZero();

        verify(marketService).updateOverride("idem-override", "currentPrice", request);
    }

    @Test
    void repurchaseDelegatesToService() {
        when(marketService.repurchaseOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G7")));

        assertThat(controller.repurchase().getData()).containsEntry("domain", "G7");

        verify(marketService).repurchaseOverview();
    }

    @Test
    void updateRepurchaseParamDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("40", "raise apy", "superadmin");
        when(marketService.updateRepurchaseParam("idem-g7", "apy", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateRepurchaseParam("idem-g7", "apy", request).getCode()).isZero();

        verify(marketService).updateRepurchaseParam("idem-g7", "apy", request);
    }

    @Test
    void genesisDelegatesToService() {
        when(marketService.genesisOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G4")));

        assertThat(controller.genesis().getData()).containsEntry("domain", "G4");

        verify(marketService).genesisOverview();
    }

    @Test
    void updateGenesisParamDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("0.2", "raise dividend", "superadmin");
        when(marketService.updateGenesisParam("idem-g4", "dividend", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateGenesisParam("idem-g4", "dividend", request).getCode()).isZero();

        verify(marketService).updateGenesisParam("idem-g4", "dividend", request);
    }

    @Test
    void updateGenesisMarketStatusDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("off", "securities risk", "superadmin");
        when(marketService.updateGenesisMarketStatus("idem-g4-switch", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateGenesisMarketStatus("idem-g4-switch", request).getCode()).isZero();

        verify(marketService).updateGenesisMarketStatus("idem-g4-switch", request);
    }

    @Test
    void rerunGenesisDividendBatchDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("rerun", "retry failed rows", "superadmin");
        when(marketService.rerunGenesisDividendBatch("idem-g4-rerun", "GD-0611", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.rerunGenesisDividendBatch("idem-g4-rerun", "GD-0611", request).getCode()).isZero();

        verify(marketService).rerunGenesisDividendBatch("idem-g4-rerun", "GD-0611", request);
    }
}
