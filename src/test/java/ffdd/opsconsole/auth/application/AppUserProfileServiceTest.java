package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.mapper.AppUserProfileMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AppUserProfileServiceTest {
    private final AppUserProfileMapper mapper = mock(AppUserProfileMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final AppUserProfileService service = new AppUserProfileService(mapper, idempotency, audit, storage);

    @BeforeEach
    void executeIdempotentAction() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(idempotency.execute(any(), any(), any(), eq(Map.class), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Map<String, Object>> action = invocation.getArgument(4);
            return action.get();
        });
    }

    @Test
    void candidatesAreCuratedUniqueAndServerGenerated() {
        var candidates = service.nicknameCandidates(42L);

        assertThat(candidates).hasSize(6).doesNotHaveDuplicates()
                .allMatch(name -> name.matches("[A-Za-z]+ [A-Za-z]+ [1-9][0-9]"));
        verify(mapper).activeUser(42L);
    }

    @Test
    void invalidNicknameNeverTouchesIdentityOrAudit() {
        when(mapper.activeUser(42L)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateNickname(
                42L, "profile-key", new AppUserProfileService.UpdateNicknameRequest("Nexion 0042", "free text")))
                .hasMessage("USER_NICKNAME_NOT_CURATED");

        verify(mapper, never()).currentNicknameForUpdate(any());
        verify(mapper, never()).updateNickname(any(), any(), any());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void updateUsesExpectedNicknameCasAndReturnsAuthoritativeProjection() {
        when(mapper.activeUser(42L)).thenReturn(1L);
        when(mapper.currentNicknameForUpdate(42L)).thenReturn("Nexion 0042");
        when(mapper.updateNickname(42L, "Nova Rover 42", "Nexion 0042")).thenReturn(1);

        Map<String, Object> result = service.updateNickname(
                42L, "profile-key", new AppUserProfileService.UpdateNicknameRequest("Nexion 0042", "Nova Rover 42"));

        assertThat(result).containsEntry("nickname", "Nova Rover 42").containsEntry("status", "UPDATED");
        verify(mapper).updateNickname(42L, "Nova Rover 42", "Nexion 0042");
        verify(audit).recordRequired(any());
    }

    @Test
    void updatesOnlyTheAuthenticatedActiveUsersWhitelistedLanguage() {
        when(mapper.updateLanguage(42L, "zh")).thenReturn(1);

        Map<String, Object> result = service.updateLanguage(42L, "zh");

        assertThat(result).containsEntry("language", "zh");
        verify(mapper).updateLanguage(42L, "zh");
        verify(mapper, never()).updateLanguage(org.mockito.ArgumentMatchers.eq(43L), any());
    }

    @Test
    void refusesUnknownLanguageBeforeItCanReachTheUserRow() {
        assertThatThrownBy(() -> service.updateLanguage(42L, "zh-CN"))
                .hasMessage("USER_LANGUAGE_INVALID");

        verify(mapper, never()).updateLanguage(any(), any());
    }

    @Test
    void profileNormalizesLegacyRegionalLanguageTagsWithoutChangingTheStoredValue() {
        when(mapper.profile(42L)).thenReturn(Map.of("nickname", "Nexion 0042", "avatarObjectKey", "", "language", "zh-CN"));
        assertThat(service.profile(42L)).containsEntry("language", "zh");

        when(mapper.profile(42L)).thenReturn(Map.of("nickname", "Nexion 0042", "avatarObjectKey", "", "language", "vi-VN"));
        assertThat(service.profile(42L)).containsEntry("language", "vi");
        verify(mapper, never()).updateLanguage(any(), any());
    }

    @Test
    void avatarAcceptsOnlyMagicVerifiedImagesAndStoresAnOpaqueObjectKey() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        when(mapper.currentAvatarForUpdate(42L)).thenReturn(null);
        when(mapper.updateAvatarObjectKey(eq(42L), any(), eq(null))).thenReturn(1);
        when(storage.presignGet(any(), any())).thenReturn("https://objects.example/avatar");

        Map<String, Object> result = service.uploadAvatar(42L, "avatar-key",
                new MockMultipartFile("file", "avatar.png", "text/plain", png));

        assertThat(result).containsEntry("status", "UPDATED");
        verify(storage).put(org.mockito.ArgumentMatchers.matches("users/42/avatar/[a-f0-9]{32}\\.png"),
                eq("image/png"), any(), eq((long) png.length));
        verify(mapper).updateAvatarObjectKey(eq(42L), any(), eq(null));
        verify(audit).recordRequired(any());
    }

    @Test
    void spoofedAvatarNeverTouchesStorageOrUserRow() {
        assertThatThrownBy(() -> service.uploadAvatar(42L, "avatar-key",
                new MockMultipartFile("file", "avatar.png", "image/png", "<svg/>".getBytes())))
                .hasMessage("USER_AVATAR_TYPE_INVALID");

        verify(storage, never()).put(any(), any(), any(), any(Long.class));
        verify(mapper, never()).updateAvatarObjectKey(any(), any(), any());
    }
}
