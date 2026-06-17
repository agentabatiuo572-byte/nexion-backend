package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.OpsTeamService;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsTeamControllerTest {
    private final OpsTeamService teamService = mock(OpsTeamService.class);
    private final OpsTeamController controller = new OpsTeamController(teamService);

    @Test
    void overviewDelegatesToService() {
        when(teamService.overview()).thenReturn(ApiResult.ok(Map.of("domain", "F")));

        ApiResult<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F");
    }

    @Test
    void updateConfigTakesKeyFromPathAndPassesIdempotencyKey() {
        controller.updateConfig(
                "directRoyaltyPct",
                "idem-f2",
                new TeamCommissionConfigUpdateRequest(null, "8", "tighten payout", "superadmin"));

        verify(teamService).updateConfig(eq("idem-f2"), any(TeamCommissionConfigUpdateRequest.class));
    }
}
