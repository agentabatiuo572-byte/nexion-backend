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
import ffdd.opsconsole.shared.storage.StorageProperties;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AppUserProfileServiceTest {
    @Test
    void unsupportedLegacyLocaleReadsAsEnglishAndCannotBeSavedAgain() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.profile(42L)).thenReturn(Map.of("language", "ja", "nickname", "Nova Rover 42"));
        assertThat(service.profile(42L)).containsEntry("language", "en");
        assertThatThrownBy(() -> service.updateLanguage(42L, "ja")).hasMessage("USER_LANGUAGE_INVALID");
        verify(mapper, never()).updateLanguage(42L, "ja");
    }

    private final AppUserProfileMapper mapper = mock(AppUserProfileMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ffdd.opsconsole.growth.application.H3DayOneBusinessFactService facts = mock(ffdd.opsconsole.growth.application.H3DayOneBusinessFactService.class);
    private final StorageProperties storageProperties = new StorageProperties();
    private final AppUserAvatarImageService avatarImages = avatarImages();
    private final AppUserProfileService service = new AppUserProfileService(mapper, idempotency, audit, storage, facts, avatarImages);

    private AppUserAvatarImageService avatarImages() {
        storageProperties.setPublicMediaOrigin("https://avatar-test.example");
        storageProperties.setSecretKey("test-only-avatar-signing-secret");
        return new AppUserAvatarImageService(mapper, storage, storageProperties);
    }

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
        verify(facts).record(42L, ffdd.opsconsole.growth.application.H3DayOneBusinessFactContract.PROFILE_SAVED);
        verify(audit).recordRequired(any());
    }

    @Test
    void unchangedOrStaleNicknameDoesNotCreateACompletionFact() {
        when(mapper.currentNicknameForUpdate(42L)).thenReturn("Nova Rover 42");
        assertThat(service.updateNickname(42L, "same", new AppUserProfileService.UpdateNicknameRequest(
                "wrong old nickname", "Nova Rover 42"))).containsEntry("status", "UNCHANGED");
        assertThatThrownBy(() -> service.updateNickname(42L, "stale", new AppUserProfileService.UpdateNicknameRequest(
                "wrong old nickname", "Swift Pilot 43"))).hasMessage("USER_PROFILE_VERSION_CONFLICT");
        org.mockito.Mockito.verifyNoInteractions(facts);
    }

    @Test
    void failedNicknameWriteNeverCreatesACompletionFact() {
        when(mapper.currentNicknameForUpdate(42L)).thenReturn("Nexion 0042");
        when(mapper.updateNickname(42L, "Swift Pilot 43", "Nexion 0042")).thenReturn(0);

        assertThatThrownBy(() -> service.updateNickname(42L, "failed-write",
                new AppUserProfileService.UpdateNicknameRequest("Nexion 0042", "Swift Pilot 43")))
                .hasMessage("USER_PROFILE_VERSION_CONFLICT");

        verify(facts, never()).record(any(), any());
    }

    @Test
    void canonicalFactFailureDoesNotReturnAFalseProfileSuccess() {
        when(mapper.currentNicknameForUpdate(42L)).thenReturn("Nexion 0042");
        when(mapper.updateNickname(42L, "Swift Pilot 43", "Nexion 0042")).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox unavailable"))
                .when(facts).record(42L,
                        ffdd.opsconsole.growth.application.H3DayOneBusinessFactContract.PROFILE_SAVED);

        assertThatThrownBy(() -> service.updateNickname(42L, "fact-failed",
                new AppUserProfileService.UpdateNicknameRequest("Nexion 0042", "Swift Pilot 43")))
                .hasMessage("outbox unavailable");
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
    void avatarReadAndUploadMustUseThePublicApiOrigin() {
        String key = "users/42/avatar/a76f323e5177470f86471a42bb8fcb62.png";
        when(mapper.profile(42L)).thenReturn(Map.of("nickname", "Nova Rover 42",
                "avatarObjectKey", key, "language", "en"));
        when(storage.presignGet(any(), any())).thenReturn("http://127.0.0.1:9000/nexion/" + key);

        assertThat(service.profile(42L).get("avatarUrl"))
                .as("the authoritative profile must issue a browser-reachable avatar URL")
                .isInstanceOf(String.class)
                .asString().startsWith("https://avatar-test.example/api/app/profile/avatar/image/42/");
        assertThat(service.profile(42L).get("avatarRevision"))
                .isEqualTo(java.util.HexFormat.of().formatHex(digest(key)));

        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        when(mapper.currentAvatarForUpdate(42L)).thenReturn(null);
        when(mapper.updateAvatarObjectKey(eq(42L), any(), eq(null))).thenReturn(1);
        assertThat(service.uploadAvatar(42L, "public-avatar", new MockMultipartFile(
                "file", "test-avatar.png", "image/png", png)).get("avatarUrl"))
                .isInstanceOf(String.class)
                .asString().startsWith("https://avatar-test.example/api/app/profile/avatar/image/42/");
        verify(storage, never()).presignGet(any(), any());
    }

    private byte[] digest(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void missingPublicOriginFailsBeforeAnyAvatarMutation() {
        storageProperties.setPublicMediaOrigin("");
        assertThatThrownBy(() -> service.uploadAvatar(42L, "missing-origin", new MockMultipartFile(
                "file", "avatar.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})))
                .hasMessage("USER_AVATAR_IMAGE_PUBLIC_ORIGIN_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(storage, audit, facts);
        verify(mapper, never()).updateAvatarObjectKey(any(), any(), any());
    }

    @Test
    void avatarAcceptsOnlyMagicVerifiedImagesAndStoresAnOpaqueObjectKey() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        when(mapper.currentAvatarForUpdate(42L)).thenReturn(null);
        when(mapper.updateAvatarObjectKey(eq(42L), any(), eq(null))).thenReturn(1);

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
