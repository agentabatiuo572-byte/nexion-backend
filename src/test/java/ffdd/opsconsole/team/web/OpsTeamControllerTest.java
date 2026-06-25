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
import ffdd.opsconsole.team.dto.VRankRewardRequest;
import java.math.BigDecimal;
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

    @Test
    void ratesDelegatesToService() {
        when(teamService.rates()).thenReturn(ApiResult.ok(Map.of("domain", "F2")));

        ApiResult<Map<String, Object>> result = controller.rates();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F2");
        verify(teamService).rates();
    }

    @Test
    void commissionsDelegatesToService() {
        when(teamService.commissions()).thenReturn(ApiResult.ok(Map.of("domain", "F5")));

        ApiResult<Map<String, Object>> result = controller.commissions();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F5");
        verify(teamService).commissions();
    }

    @Test
    void binaryDelegatesToService() {
        when(teamService.binary()).thenReturn(ApiResult.ok(Map.of("domain", "F3")));

        ApiResult<Map<String, Object>> result = controller.binary();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F3");
        verify(teamService).binary();
    }

    @Test
    void leadershipPoolDelegatesToService() {
        when(teamService.leadershipPool()).thenReturn(ApiResult.ok(Map.of("domain", "F4")));

        ApiResult<Map<String, Object>> result = controller.leadershipPool();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F4");
        verify(teamService).leadershipPool();
    }

    @Test
    void updateVRankThresholdPassesRankFieldAndIdempotencyKey() {
        controller.updateVRankThreshold(
                "V3",
                "teamGv",
                "idem-f1",
                new TeamCommissionConfigUpdateRequest(null, "$25k", "raise v3 gate", "superadmin"));

        verify(teamService).updateVRankThreshold(eq("V3"), eq("teamGv"), eq("idem-f1"), any(TeamCommissionConfigUpdateRequest.class));
    }

    @Test
    void rewardCrudDelegatesToService() {
        VRankRewardRequest request = new VRankRewardRequest("usdt", new BigDecimal("100"), null, null, null, "add reward", "superadmin");

        controller.addVRankReward("V3", "idem-add", request);
        controller.updateVRankReward("V3", "reward-1", "idem-update", request);
        controller.removeVRankReward("V3", "reward-1", "idem-remove", request);

        verify(teamService).addVRankReward(eq("V3"), eq("idem-add"), eq(request));
        verify(teamService).updateVRankReward(eq("V3"), eq("reward-1"), eq("idem-update"), eq(request));
        verify(teamService).removeVRankReward(eq("V3"), eq("reward-1"), eq("idem-remove"), eq(request));
    }
}
