package ffdd.opsconsole.janus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.application.OpsJanusService;
import ffdd.opsconsole.janus.dto.JanusCommandAckRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppJanusControllerTest {
    private final OpsJanusService service = mock(OpsJanusService.class);
    private final AppJanusController controller = new AppJanusController(service);

    @Test
    void adminTokenCannotImpersonateDeviceReportingUser() {
        var auth = new UsernamePasswordAuthenticationToken("1", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "ADMIN"));

        ApiResult<Map<String, Object>> result = controller.pending("D-1", auth);

        assertThat(result.getCode()).isEqualTo(403);
    }

    @Test
    void userTokenBindsAckToAuthenticatedUserId() {
        var auth = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        JanusCommandAckRequest request = new JanusCommandAckRequest("D-1", 2L, true, "HIT", "ok");
        when(service.acknowledgeCommand(eq(42L), eq(request)))
                .thenReturn(ApiResult.ok(Map.of("state", "ACKED")));

        ApiResult<Map<String, Object>> result = controller.acknowledge(request, auth);

        assertThat(result.getCode()).isZero();
        verify(service).acknowledgeCommand(42L, request);
    }
}
