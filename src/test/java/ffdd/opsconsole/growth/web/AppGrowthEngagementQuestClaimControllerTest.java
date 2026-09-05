package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppGrowthEngagementService;
import ffdd.opsconsole.growth.application.AppGrowthWheelService;
import ffdd.opsconsole.growth.application.AppReferralRewardService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppGrowthEngagementQuestClaimControllerTest {
    private final AppGrowthEngagementService service = mock(AppGrowthEngagementService.class);
    private final AppGrowthEngagementController controller = new AppGrowthEngagementController(
            service, mock(AppGrowthWheelService.class), mock(AppReferralRewardService.class));

    @Test
    void bindsTheRequestBodyInstanceAndIdempotencyHeaderToTheAuthenticatedUser() {
        var authentication = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        var request = new AppGrowthEngagementController.QuestClaimRequest("WEEK:2026-W36");
        when(service.claimQuest(42L, "H3_DEVICE_ACTIVATED", "WEEK:2026-W36", "weekly-key"))
                .thenReturn(ApiResult.ok(Map.of("status", "CLAIMED")));

        ApiResult<Map<String, Object>> result = controller.claimQuest(
                "H3_DEVICE_ACTIVATED", request, "weekly-key", authentication);

        assertThat(result.getCode()).isZero();
        verify(service).claimQuest(42L, "H3_DEVICE_ACTIVATED", "WEEK:2026-W36", "weekly-key");
    }
}
