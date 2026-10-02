package ffdd.opsconsole.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.application.AppUserProfileService;
import ffdd.opsconsole.auth.application.AppUserAvatarImageService;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.Authentication;
import org.springframework.mock.web.MockMultipartFile;

class AppUserProfileControllerTest {
    @Test
    void avatarReadAndUploadUseOnlyTheAuthenticatedUserSubject() {
        AppUserProfileService service = mock(AppUserProfileService.class);
        AppUserProfileController controller = new AppUserProfileController(service, mock(AppUserAvatarImageService.class));
        var file = new MockMultipartFile("file", "test.png", "image/png", new byte[] {1});
        when(service.profile(42L)).thenReturn(Map.of("avatarUrl", "https://avatar-test.example/current"));
        when(service.uploadAvatar(42L, "avatar-key", file)).thenReturn(Map.of("status", "UPDATED"));

        assertThat(controller.profile(userAuthentication(42L)).getData())
                .containsEntry("avatarUrl", "https://avatar-test.example/current");
        assertThat(controller.uploadAvatar(userAuthentication(42L), "avatar-key", file).getData())
                .containsEntry("status", "UPDATED");
        verify(service).profile(42L);
        verify(service).uploadAvatar(42L, "avatar-key", file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "ADMIN", "0", "-1", "not-a-user-id"})
    void profileAndAvatarWritesCannotUseAnonymousAdminOrMalformedSubjects(String subject) {
        AppUserProfileService service = mock(AppUserProfileService.class);
        AppUserProfileController controller = new AppUserProfileController(service, mock(AppUserAvatarImageService.class));
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(!subject.equals("anonymous"));
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", subject.equals("ADMIN") ? "ADMIN" : "USER"));
        when(authentication.getName()).thenReturn(subject);
        assertThatThrownBy(() -> controller.profile(authentication)).hasMessage("USER_AUTH_REQUIRED");
        assertThatThrownBy(() -> controller.uploadAvatar(authentication, "test-key", null))
                .hasMessage("USER_AUTH_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void languageUpdateUsesAuthenticatedSubjectInsteadOfClientProvidedAccount() {
        AppUserProfileService service = mock(AppUserProfileService.class);
        AppUserProfileController controller = new AppUserProfileController(service, mock(AppUserAvatarImageService.class));
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
        AppUserProfileController controller = new AppUserProfileController(service, mock(AppUserAvatarImageService.class));
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
