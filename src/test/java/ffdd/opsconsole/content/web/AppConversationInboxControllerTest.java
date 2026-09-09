package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.application.AppConversationInboxService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppConversationInboxControllerTest {
    final AppConversationInboxService service = mock(AppConversationInboxService.class);
    final AppConversationInboxController controller = new AppConversationInboxController(service);

    @Test void onlyAuthenticatedUserSubjectCanReadOrWriteOwnPreferences() {
        assertThat(controller.list(null).getCode()).isEqualTo(403);
        var admin = UsernamePasswordAuthenticationToken.authenticated("7", null, java.util.List.of());
        admin.setDetails(Map.of("subjectType", "ADMIN"));
        assertThat(controller.dismiss("CV-A", new AppConversationInboxController.DismissRequest(11L), admin).getCode()).isEqualTo(403);
        verifyNoInteractions(service);
        var user = UsernamePasswordAuthenticationToken.authenticated("7", null, java.util.List.of());
        user.setDetails(Map.of("subjectType", "USER"));
        when(service.dismiss(7L,"CV-A",11L)).thenReturn(ApiResult.ok(null));
        assertThat(controller.dismiss("CV-A", new AppConversationInboxController.DismissRequest(11L), user).getCode()).isZero();
        verify(service).dismiss(7L,"CV-A",11L);
    }
}
