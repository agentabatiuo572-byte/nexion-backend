package ffdd.opsconsole.home.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ffdd.opsconsole.home.application.AppHomeOverviewService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class AppHomeOverviewControllerTest {
    private final AppHomeOverviewService service = mock(AppHomeOverviewService.class);
    private final AppHomeOverviewController controller = new AppHomeOverviewController(service);

    @Test
    void unauthenticatedRequestIsRejectedBeforeService() {
        ApiResult<Map<String, Object>> result = controller.overview(null);
        assertEquals(403, result.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void userSubjectIsDelegated() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("42", "token", java.util.List.of());
        // The controller requires the same subject marker used by the other App controllers.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("subjectType", "USER");
        ((UsernamePasswordAuthenticationToken) authentication).setDetails(details);
        whenResult();
        controller.overview(authentication);
        verify(service).overview(42L);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void whenResult() {
        org.mockito.Mockito.when(service.overview(42L)).thenReturn(ApiResult.ok(Map.of()));
    }
}
