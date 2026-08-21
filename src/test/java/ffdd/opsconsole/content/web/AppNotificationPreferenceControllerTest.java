package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.application.NotificationPreferenceService;
import ffdd.opsconsole.content.domain.NotificationPreferenceView;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AppNotificationPreferenceControllerTest {
    private final NotificationPreferenceService service = mock(NotificationPreferenceService.class);
    private final AppNotificationPreferenceController controller = new AppNotificationPreferenceController(service);

    @Test
    void adminOrMissingSessionCannotReadOrPatchPreferences() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "ADMIN"));
        when(service.get(null)).thenReturn(ApiResult.fail(403, "USER_AUTH_REQUIRED"));
        when(service.patch(null, null)).thenReturn(ApiResult.fail(403, "USER_AUTH_REQUIRED"));

        assertThat(controller.get(authentication).getCode()).isEqualTo(403);
        assertThat(controller.patch(null, authentication).getCode()).isEqualTo(403);
        verify(service).get(null);
        verify(service).patch(null, null);
    }

    @Test
    void userIdentityIsTakenFromSecurityContextAndNeverFromRequestBody() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        var view = NotificationPreferenceView.allEnabled();
        when(service.get(7L)).thenReturn(ApiResult.ok(view));
        when(service.patch(eq(7L), any())).thenReturn(ApiResult.ok(view));

        assertThat(controller.get(authentication).getData()).isEqualTo(view);
        assertThat(controller.patch(Map.of("commission", false), authentication).getData()).isEqualTo(view);
        verify(service).get(7L);
        verify(service).patch(eq(7L), any());
    }

    @Test
    void unknownCategoryIsRejectedAndNeverForwardedToTheService() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));

        var result = controller.patch(Map.of("unknown", true), authentication);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_PREFERENCES_CATEGORY_INVALID");
        verifyNoInteractions(service);
    }

    @Test
    void emptyPatchIsRejectedByTheServiceContract() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        when(service.patch(7L, null)).thenReturn(ApiResult.fail(422, "NOTIFICATION_PREFERENCES_PATCH_EMPTY"));

        var result = controller.patch(Map.of(), authentication);

        assertThat(result.getCode()).isEqualTo(422);
        verify(service).patch(7L, null);
    }
}
