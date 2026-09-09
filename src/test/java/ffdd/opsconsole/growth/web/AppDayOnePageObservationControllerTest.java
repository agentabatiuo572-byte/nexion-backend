package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.H3DayOnePageObservationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppDayOnePageObservationControllerTest {
    private final H3DayOnePageObservationService service = mock(H3DayOnePageObservationService.class);
    private final AppDayOnePageObservationController controller = new AppDayOnePageObservationController(service);

    @Test
    void usesOnlyTheAuthenticatedUserSubject() {
        when(service.observe(42L, "earn")).thenReturn(ApiResult.ok(Map.of("accepted", true, "recorded", true)));

        var result = controller.observe("earn", auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        verify(service).observe(42L, "earn");
    }

    @Test
    void rejectsAdminSubjectsBeforeTheyReachTheObservationService() {
        var result = controller.observe("store", auth("7", "ADMIN"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(service, never()).observe(7L, "store");
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(id, null, java.util.List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
