package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsStakingControllerTest {
    private final OpsNexMarketService marketService = mock(OpsNexMarketService.class);
    private final OpsStakingController controller = new OpsStakingController(marketService);

    @Test
    void overviewDelegatesToService() {
        when(marketService.stakingOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G1")));

        assertThat(controller.overview().getData()).containsEntry("domain", "G1");

        verify(marketService).stakingOverview();
    }

    @Test
    void updatePoolParamDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("40", "raise apy", "superadmin");
        when(marketService.updateStakingPoolParam("idem-g1", "usdt90d", "apy", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePoolParam("idem-g1", "usdt90d", "apy", request).getCode()).isZero();

        verify(marketService).updateStakingPoolParam("idem-g1", "usdt90d", "apy", request);
    }

    @Test
    void updatePoolSaleStatusDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("false", "stop sale", "superadmin");
        when(marketService.updateStakingPoolSaleStatus("idem-g1-sale", "usdt365d", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePoolSaleStatus("idem-g1-sale", "usdt365d", request).getCode()).isZero();

        verify(marketService).updateStakingPoolSaleStatus("idem-g1-sale", "usdt365d", request);
    }

    @Test
    void updatePoolKillStatusDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("true", "incident plan", "superadmin");
        when(marketService.updateStakingPoolKillStatus("idem-g1-kill", "nex365d", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePoolKillStatus("idem-g1-kill", "nex365d", request).getCode()).isZero();

        verify(marketService).updateStakingPoolKillStatus("idem-g1-kill", "nex365d", request);
    }
}
