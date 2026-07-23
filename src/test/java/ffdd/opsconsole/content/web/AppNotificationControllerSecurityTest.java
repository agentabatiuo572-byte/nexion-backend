package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppNotificationService;
import ffdd.opsconsole.content.domain.NotificationActionResult;
import ffdd.opsconsole.content.dto.NotificationActionRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AppNotificationControllerSecurityTest {
    private final AppNotificationService service = mock(AppNotificationService.class);
    private final AppNotificationController controller = new AppNotificationController(service);

    @Test
    void adminSessionCannotActOnAUsersNotification() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "ADMIN"));
        when(service.recordAction(null, 99L, "cta", "idem-action-100"))
                .thenReturn(ApiResult.fail(403, "USER_AUTH_REQUIRED"));

        var result = controller.recordAction(
                99L, "idem-action-100", new NotificationActionRequest("cta"), authentication);

        assertThat(result.getCode()).isEqualTo(403);
        verify(service).recordAction(null, 99L, "cta", "idem-action-100");
    }

    @Test
    void authenticatedUserIdentityComesFromTheSecurityContext() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        when(service.recordAction(7L, 99L, "cta", "idem-action-101"))
                .thenReturn(ApiResult.ok(new NotificationActionResult(
                        99L, "cta", "/pages/me/kyc", true)));

        var result = controller.recordAction(
                99L, "idem-action-101", new NotificationActionRequest("cta"), authentication);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().route()).isEqualTo("/pages/me/kyc");
        verify(service).recordAction(7L, 99L, "cta", "idem-action-101");
    }
}
