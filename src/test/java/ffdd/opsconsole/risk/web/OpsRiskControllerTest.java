package ffdd.opsconsole.risk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsRiskControllerTest {
    private final OpsRiskService riskService = mock(OpsRiskService.class);
    private final OpsRiskController controller = new OpsRiskController(riskService);

    @Test
    void overviewDelegatesToService() {
        when(riskService.overview()).thenReturn(ApiResult.ok(Map.of("domain", "K")));

        ApiResult<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "K");
    }

    @Test
    void decisionEndpointPassesIdempotencyKey() {
        controller.decide("RD-1", "idem-k", new RiskDecisionRequest("BLOCK", "fraud evidence", "superadmin"));

        verify(riskService).decide(eq("RD-1"), eq("idem-k"), any(RiskDecisionRequest.class));
    }
}
