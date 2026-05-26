package ffdd.bff.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.bff.client.CommerceOpsClient;
import ffdd.bff.client.ComplianceClient;
import ffdd.bff.client.OpenApiOpsClient;
import ffdd.bff.client.WalletOpsClient;
import ffdd.common.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BffOpsControllerTest {
    private final CommerceOpsClient commerceClient = mock(CommerceOpsClient.class);
    private final WalletOpsClient walletClient = mock(WalletOpsClient.class);
    private final ComplianceClient complianceClient = mock(ComplianceClient.class);
    private final OpenApiOpsClient openApiOpsClient = mock(OpenApiOpsClient.class);
    private final BffOpsController controller =
            new BffOpsController(commerceClient, walletClient, complianceClient, openApiOpsClient);

    @Test
    @SuppressWarnings("unchecked")
    void dashboardAggregatesNativeServiceStatsWithBoundedDays() {
        when(commerceClient.opsStats(90)).thenReturn(ApiResult.ok(Map.of("orders", Map.of("total", 12L))));
        when(walletClient.opsStats(90)).thenReturn(ApiResult.ok(Map.of("withdrawals", Map.of("dead", 1L))));
        when(complianceClient.opsStats(90)).thenReturn(ApiResult.ok(Map.of("risk", Map.of("reviewQueue", 3L))));
        when(openApiOpsClient.opsStats(90)).thenReturn(ApiResult.ok(Map.of("calls", Map.of("failed", 8L))));

        ApiResult<Map<String, Object>> result = controller.dashboard(120);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("service", "nexion-bff-service")
                .containsEntry("days", 90)
                .containsKey("upstreams");
        Map<String, Object> upstreams = (Map<String, Object>) result.getData().get("upstreams");
        assertThat(upstreams).containsKeys("commerce", "wallet", "compliance", "openapi");
        verify(commerceClient).opsStats(90);
        verify(walletClient).opsStats(90);
        verify(complianceClient).opsStats(90);
        verify(openApiOpsClient).opsStats(90);
    }
}
