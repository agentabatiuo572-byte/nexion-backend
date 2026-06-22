package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueCancelRequest;
import ffdd.opsconsole.market.dto.ExchangeSwapStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsExchangeControllerTest {
    private final OpsNexMarketService marketService = mock(OpsNexMarketService.class);
    private final OpsExchangeController controller = new OpsExchangeController(marketService);

    @Test
    void overviewDelegatesToService() {
        when(marketService.exchangeOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G2")));

        assertThat(controller.overview().getData()).containsEntry("domain", "G2");

        verify(marketService).exchangeOverview();
    }

    @Test
    void orderDetailDelegatesToService() {
        when(marketService.exchangeOrderDetail("EX-1")).thenReturn(ApiResult.ok(Map.of("exchangeNo", "EX-1")));

        assertThat(controller.orderDetail("EX-1").getData()).containsEntry("exchangeNo", "EX-1");

        verify(marketService).exchangeOrderDetail("EX-1");
    }

    @Test
    void updateParamDelegatesWithIdempotencyKey() {
        ExchangeParamUpdateRequest request = new ExchangeParamUpdateRequest("100", "raise cap", "superadmin");
        when(marketService.updateExchangeParam("idem-g2", "userDailyCap", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateParam("idem-g2", "userDailyCap", request).getCode()).isZero();

        verify(marketService).updateExchangeParam("idem-g2", "userDailyCap", request);
    }

    @Test
    void updateSwapDelegatesWithIdempotencyKey() {
        ExchangeSwapStatusRequest request = new ExchangeSwapStatusRequest(false, "pause exchange", "superadmin");
        when(marketService.updateExchangeSwapStatus("idem-swap", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateSwapStatus("idem-swap", request).getCode()).isZero();

        verify(marketService).updateExchangeSwapStatus("idem-swap", request);
    }

    @Test
    void cancelQueueDelegatesWithIdempotencyKey() {
        ExchangeQueueCancelRequest request = new ExchangeQueueCancelRequest("geo blocked", "superadmin");
        when(marketService.cancelExchangeQueueOrder("idem-cancel", "EX-Q-1", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.cancelQueueOrder("idem-cancel", "EX-Q-1", request).getCode()).isZero();

        verify(marketService).cancelExchangeQueueOrder("idem-cancel", "EX-Q-1", request);
    }
}
