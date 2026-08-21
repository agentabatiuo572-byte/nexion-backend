package ffdd.opsconsole.janus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.application.JanusSandboxEnrollmentService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppJanusSandboxEnrollmentControllerTest {
    private final JanusSandboxEnrollmentService service = mock(JanusSandboxEnrollmentService.class);
    private final AppJanusSandboxEnrollmentController controller = new AppJanusSandboxEnrollmentController(service);

    @Test
    void enrollmentIsBoundToAuthenticatedUserRatherThanAClientSubject() {
        var auth = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        var issue = new JanusSandboxEnrollmentService.Issue("42", "device-42", "token", 2_000L, Set.of("approved"));
        when(service.issue(42L, "device-42")).thenReturn(issue);

        var result = controller.enroll(new AppJanusSandboxEnrollmentController.EnrollmentRequest("device-42"), auth);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(issue);
    }

    @Test
    void adminCannotEnrollAsAnAppUser() {
        var auth = new UsernamePasswordAuthenticationToken("1", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "ADMIN"));
        assertThat(controller.enroll(new AppJanusSandboxEnrollmentController.EnrollmentRequest("device-1"), auth)
                .getCode()).isEqualTo(403);
    }
}
