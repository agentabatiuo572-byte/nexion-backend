package ffdd.opsconsole.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.application.AppUserProfileService;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AppUserProfileControllerTest {
    @Test
    void languageUpdateUsesAuthenticatedSubjectInsteadOfClientProvidedAccount() {
        AppUserProfileService service = mock(AppUserProfileService.class);
        AppUserProfileController controller = new AppUserProfileController(service);
        Authentication authentication = userAuthentication(42L);
        when(service.updateLanguage(42L, "zh")).thenReturn(Map.of("language", "zh", "status", "UPDATED"));

        var response = controller.updateLanguage(authentication,
                new AppUserProfileController.UpdateLanguageRequest("zh"));

        assertThat(response.getData()).containsEntry("language", "zh");
        verify(service).updateLanguage(42L, "zh");
    }

    @Test
    void languageUpdateRejectsNonUserAuthentication() {
        AppUserProfileService service = mock(AppUserProfileService.class);
        AppUserProfileController controller = new AppUserProfileController(service);
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "ADMIN"));

        assertThatThrownBy(() -> controller.updateLanguage(authentication,
                new AppUserProfileController.UpdateLanguageRequest("zh")))
                .isInstanceOf(BizException.class)
                .hasMessage("USER_AUTH_REQUIRED");
    }

    private Authentication userAuthentication(long userId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(String.valueOf(userId));
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        return authentication;
    }
}
