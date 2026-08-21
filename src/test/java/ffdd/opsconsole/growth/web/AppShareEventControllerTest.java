package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppGrowthEngagementService;
import ffdd.opsconsole.growth.application.AppGrowthWheelService;
import ffdd.opsconsole.growth.application.AppReferralRewardService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppShareEventControllerTest {
    private final AppGrowthEngagementService service = mock(AppGrowthEngagementService.class);
    private final AppGrowthEngagementController controller = new AppGrowthEngagementController(
            service, mock(AppGrowthWheelService.class), mock(AppReferralRewardService.class));

    @Test
    void shareEndpointUsesAuthenticatedSubjectAndDoesNotAcceptBodyUserId() {
        var auth = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        var request = new AppGrowthEngagementService.ShareEventRequest(
                "share-evt-100", "telegram", "share_sheet", "PRODUCTION", "");
        when(service.recordShareEvent(42L, request, "share-key-100"))
                .thenReturn(ApiResult.ok(Map.of("eventId", "share-evt-100")));

        ApiResult<Map<String, Object>> result = controller.recordShareEvent(
                request, "share-key-100", auth);

        assertThat(result.getCode()).isZero();
        verify(service).recordShareEvent(42L, request, "share-key-100");
    }

    @Test
    void shareEndpointRejectsMissingOrAdminSubject() {
        var admin = new UsernamePasswordAuthenticationToken("42", "n/a");
        admin.setDetails(Map.of("subjectType", "ADMIN"));
        assertThat(controller.recordShareEvent(null, "key", admin).getCode()).isEqualTo(403);
        assertThat(controller.recordShareEvent(null, "key", null).getCode()).isEqualTo(403);
    }
}
