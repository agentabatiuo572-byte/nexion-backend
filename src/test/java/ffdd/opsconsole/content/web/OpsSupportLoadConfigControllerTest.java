package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.application.OpsSupportLoadConfigService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsSupportLoadConfigControllerTest {
    private final OpsSupportLoadConfigService loadConfigService = mock(OpsSupportLoadConfigService.class);
    private final OpsSupportAgentService supportAgentService = mock(OpsSupportAgentService.class);
    private final ProductionSupportPathGuard productionPathGuard = mock(ProductionSupportPathGuard.class);
    private final OpsSupportLoadConfigController controller =
            new OpsSupportLoadConfigController(loadConfigService, supportAgentService, productionPathGuard);

    @Test
    void loadConfigDelegatesToService() {
        when(loadConfigService.loadConfig()).thenReturn(ApiResult.ok(Map.of("defaultCap", 8)));

        assertThat(controller.loadConfig().getData()).containsEntry("defaultCap", 8);

        verify(loadConfigService).loadConfig();
    }

    @Test
    void updateLoadConfigDelegatesWithIdempotencyHeader() {
        SupportLoadConfigUpdateRequest request =
                new SupportLoadConfigUpdateRequest(1L, true, 7, 11, 75, true, "备勤队列", Map.of(), "superadmin", "rebalance load");
        when(loadConfigService.updateLoadConfig("idem-m1-load", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));
        when(supportAgentService.canManageSupportSeats()).thenReturn(true);

        assertThat(controller.updateLoadConfig("idem-m1-load", request).getData()).containsEntry("ok", true);

        verify(loadConfigService).updateLoadConfig("idem-m1-load", request);
    }

    @Test
    void rebalanceLoadDelegatesWithIdempotencyHeader() {
        SupportLoadRebalanceRequest request =
                new SupportLoadRebalanceRequest(1L, java.util.List.of(Map.of("id", "agent-1", "cap", 4)), "superadmin", "rebalance load");
        when(loadConfigService.rebalanceLoad("idem-m1-rebalance", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));
        when(supportAgentService.canManageSupportSeats()).thenReturn(true);

        assertThat(controller.rebalanceLoad("idem-m1-rebalance", request).getData()).containsEntry("ok", true);

        verify(loadConfigService).rebalanceLoad("idem-m1-rebalance", request);
    }

    @Test
    void loadConfigMutationRejectsNonSupervisorEvenWithWriteAuthority() {
        SupportLoadConfigUpdateRequest request =
                new SupportLoadConfigUpdateRequest(1L, true, 7, 11, 75, true, "备勤队列", Map.of(), "risk", "unauthorized load change");
        when(supportAgentService.canManageSupportSeats()).thenReturn(false);

        assertThat(controller.updateLoadConfig("idem-m1-denied", request).getCode()).isEqualTo(403);
    }

    @Test
    void isolatedProfileBlocksLoadConfigWritesBeforeTheService() {
        org.mockito.Mockito.doThrow(new ffdd.opsconsole.shared.exception.BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionPathGuard).requireOpsWriteAllowed();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.rebalanceLoad("key", null))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        org.mockito.Mockito.verifyNoInteractions(loadConfigService);
    }

    @Test
    void loadConfigEndpointsUseM1Authorities() throws Exception {
        assertThat(OpsSupportLoadConfigController.class.getMethod("loadConfig")
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("hasAuthority('service_m1_read')");
        assertThat(OpsSupportLoadConfigController.class.getMethod(
                        "updateLoadConfig", String.class, SupportLoadConfigUpdateRequest.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("hasAuthority('service_m1_write')");
        assertThat(OpsSupportLoadConfigController.class.getMethod(
                        "rebalanceLoad", String.class, SupportLoadRebalanceRequest.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("hasAuthority('service_m1_write')");
    }

    @Test
    void historicalLoadConfigUrlsStayMapped() throws Exception {
        when(loadConfigService.loadConfig()).thenReturn(ApiResult.ok(Map.of("domain", "M1")));
        when(loadConfigService.rebalanceLoad(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ApiResult.ok(Map.of("rebalanced", true)));
        when(supportAgentService.canManageSupportSeats()).thenReturn(true);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/content/tickets/load-config"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.domain").value("M1"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/content/tickets/load-config/rebalance")
                        .header("Idempotency-Key", "idem-m1-rebalance").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"agents\":[],\"reason\":\"rebalance load\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.rebalanced").value(true));
    }
}
