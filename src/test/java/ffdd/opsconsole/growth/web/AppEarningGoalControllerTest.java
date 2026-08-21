package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppEarningGoalService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppEarningGoalControllerTest {
    private final AppEarningGoalService service = mock(AppEarningGoalService.class);
    private final AppEarningGoalController controller = new AppEarningGoalController(service);

    @Test
    void authenticatedUserIsScopedToPrincipal() {
        when(service.list(42L)).thenReturn(ApiResult.ok(new AppEarningGoalService.GoalListView(
                true, "nx_earning_goal", java.math.BigDecimal.ZERO, List.of())));

        var result = controller.list(auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        verify(service).list(42L);
    }

    @Test
    void adminSubjectCannotReadUserGoals() {
        var result = controller.list(auth("42", "ADMIN"));

        assertThat(result.getCode()).isEqualTo(403);
        verify(service, never()).list(42L);
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        var auth = new UsernamePasswordAuthenticationToken(id, null, List.of());
        auth.setDetails(Map.of("subjectType", subjectType));
        return auth;
    }
}
