package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppCanonicalBoundaryControllerTest {
    private final AppCanonicalBoundaryService service = mock(AppCanonicalBoundaryService.class);
    private final AppTrialLifecycleService trialLifecycleService = mock(AppTrialLifecycleService.class);
    private final AppCanonicalBoundaryController controller =
            new AppCanonicalBoundaryController(service, trialLifecycleService);

    @Test
    void productionDevFlagLiteralOneIsForwardedAsTamperAttempt() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.productPhase(42L, null, true)).thenReturn(ApiResult.fail(409, "PRODUCT_PHASE_OVERRIDE_REJECTED"));

        ApiResult<Map<String, Object>> result = controller.productPhase(null, "1", user);

        assertThat(result.getCode()).isEqualTo(409);
        verify(service).productPhase(42L, null, true);
    }

    @Test
    void adminTokenCannotCallUserCanonicalBoundaries() {
        UsernamePasswordAuthenticationToken admin = auth("7", "ADMIN");

        ApiResult<Map<String, Object>> result = controller.trialEligibility("CLAIMED", admin);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(trialLifecycleService, never()).state(42L);
    }

    @Test
    void orderHistoryUsesAuthenticatedUserSubjectOnly() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.orders(42L)).thenReturn(ApiResult.ok(Map.of("orders", java.util.List.of())));

        ApiResult<Map<String, Object>> result = controller.orders(user);

        assertThat(result.getCode()).isZero();
        verify(service).orders(42L);
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(id, null, java.util.List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
