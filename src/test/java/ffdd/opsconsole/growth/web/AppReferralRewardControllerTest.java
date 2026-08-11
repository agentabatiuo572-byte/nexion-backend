package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppGrowthEngagementService;
import ffdd.opsconsole.growth.application.AppGrowthWheelService;
import ffdd.opsconsole.growth.application.AppReferralRewardService;
import ffdd.opsconsole.growth.domain.AppReferralRewardView;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppReferralRewardControllerTest {
    private final AppReferralRewardService service = mock(AppReferralRewardService.class);
    private final AppGrowthEngagementController controller = new AppGrowthEngagementController(
            mock(AppGrowthEngagementService.class), mock(AppGrowthWheelService.class), service);

    @Test
    void derivesUserScopeOnlyFromAuthenticatedUserSubject() {
        AppReferralRewardView view = new AppReferralRewardView(
                "NEX-ABC", BigDecimal.TEN, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), 20, "ledger", "PRODUCTION", List.of(), Instant.now());
        when(service.snapshot(42L, 999)).thenReturn(ApiResult.ok(view));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));

        ApiResult<AppReferralRewardView> response = controller.referralRewards(999, auth);

        assertThat(response.getCode()).isZero();
        verify(service).snapshot(42L, 999);
    }

    @Test
    void rejectsAdminOrMissingSubjectInsteadOfAcceptingAUserIdParameter() {
        UsernamePasswordAuthenticationToken admin =
                new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        admin.setDetails(Map.of("subjectType", "ADMIN"));

        assertThat(controller.referralRewards(10, admin).getCode()).isEqualTo(403);
        assertThat(controller.referralRewards(10, null).getCode()).isEqualTo(403);
    }
}
