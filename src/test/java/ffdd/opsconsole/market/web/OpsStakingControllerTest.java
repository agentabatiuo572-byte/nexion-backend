package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.application.OpsNexMarketService;
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
    void updateVisibilityIsRetired() {
        assertThat(controller.updateVisibility().getCode()).isEqualTo(422);
    }

    @Test
    void updatePoolParamIsRetired() {
        assertThat(controller.updatePoolParam().getCode()).isEqualTo(422);
    }

    @Test
    void updatePoolSaleStatusIsRetired() {
        assertThat(controller.updatePoolSaleStatus().getCode()).isEqualTo(422);
    }

    @Test
    void updatePoolKillStatusIsRetired() {
        assertThat(controller.updatePoolKillStatus().getCode()).isEqualTo(422);
    }

    @Test
    void restorePoolIsRetired() {
        assertThat(controller.restorePool().getCode()).isEqualTo(422);
    }
}
