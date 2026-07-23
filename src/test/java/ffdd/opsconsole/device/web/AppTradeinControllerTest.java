package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.AppTradeinService;
import ffdd.opsconsole.device.dto.AppTradeinConfigResponse;
import ffdd.opsconsole.device.dto.AppTradeinQuoteRequest;
import ffdd.opsconsole.device.dto.AppTradeinSubmitRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppTradeinControllerTest {
    private final AppTradeinService service = mock(AppTradeinService.class);
    private final AppTradeinController controller = new AppTradeinController(service);

    @Test
    void configUsesAuthenticatedUserSubject() {
        var auth = userAuth("7");
        when(service.config(7L)).thenReturn(ApiResult.ok(new AppTradeinConfigResponse(
                true, "全部用户", List.of(), List.of(), true, 1, "nx_compute_e3_config")));

        assertThat(controller.config(auth).getCode()).isZero();
        verify(service).config(7L);
    }

    @Test
    void submitPassesUserAndIdempotencyHeader() {
        var body = new AppTradeinSubmitRequest(11L, 22L);
        controller.submit(body, "idem", userAuth("7"));
        verify(service).submit(7L, "idem", body);
    }

    @Test
    void rejectsAdminSubjectEvenWhenPrincipalIsNumeric() {
        var auth = new UsernamePasswordAuthenticationToken("7", null, List.of());
        auth.setDetails(Map.of("subjectType", "ADMIN"));

        assertThat(controller.quote(new AppTradeinQuoteRequest(11L, 22L), auth).getCode()).isEqualTo(403);
    }

    private UsernamePasswordAuthenticationToken userAuth(String id) {
        var auth = new UsernamePasswordAuthenticationToken(id, null, List.of());
        auth.setDetails(Map.of("subjectType", "USER", "sessionId", "S-1"));
        return auth;
    }
}
