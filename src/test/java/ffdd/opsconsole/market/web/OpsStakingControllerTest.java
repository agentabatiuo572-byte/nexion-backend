package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.application.G1AdminCommandService;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.ApiResultHttpStatusAdvice;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsStakingControllerTest {
    private final OpsNexMarketService marketService = mock(OpsNexMarketService.class);
    private final G1AdminCommandService commandService = mock(G1AdminCommandService.class);
    private final OpsStakingController controller = new OpsStakingController(marketService, commandService);

    @Test
    void overviewDelegatesToService() {
        when(marketService.stakingOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G1")));

        assertThat(controller.overview().getData()).containsEntry("domain", "G1");

        verify(marketService).stakingOverview();
    }

    @Test
    void updatePoolParamDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("40", "raise apy", "superadmin");
        when(commandService.updateParam("idem-g1", "usdt90d", "apy", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePoolParam("idem-g1", "usdt90d", "apy", request).getCode()).isZero();

        verify(commandService).updateParam("idem-g1", "usdt90d", "apy", request);
    }

    @Test
    void updatePoolParamWithoutIdempotencyHeaderReturnsStable422Contract() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(
                        new GlobalExceptionHandler(mock(AuditLogService.class)),
                        new ApiResultHttpStatusAdvice())
                .build();

        mvc.perform(patch("/api/admin/market/staking/pools/usdt90d/params/apy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"40","reason":"raise apy safely","operator":"forged"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void updatePoolSaleStatusDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("false", "stop sale", "superadmin");
        when(commandService.updateSaleStatus("idem-g1-sale", "usdt365d", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePoolSaleStatus("idem-g1-sale", "usdt365d", request).getCode()).isZero();

        verify(commandService).updateSaleStatus("idem-g1-sale", "usdt365d", request);
    }

    @Test
    void updatePoolKillStatusDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest(
                "true", "incident plan", "superadmin", null, "slash open positions and notify holders", "INCIDENT_RESPONSE");
        when(commandService.kill("idem-g1-kill", "usdt365d", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePoolKillStatus("idem-g1-kill", "usdt365d", request).getCode()).isZero();

        verify(commandService).kill("idem-g1-kill", "usdt365d", request);
    }
}
