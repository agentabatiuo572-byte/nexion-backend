package ffdd.opsconsole.auth.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.auth.application.AppUserAvatarImageService;
import ffdd.opsconsole.auth.application.AppUserProfileService;
import ffdd.opsconsole.auth.mapper.AppUserProfileMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AppUserProfileController.class)
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {AppUserProfileController.class, SecurityConfig.class,
        GlobalExceptionHandler.class, AppUserAvatarImageSecurityTest.Images.class})
class AppUserAvatarImageSecurityTest {
    private static final String KEY = "users/42/avatar/a76f323e5177470f86471a42bb8fcb62.png";
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS9sAAAAASUVORK5CYII=");
    @Autowired private MockMvc mockMvc;
    @SpyBean private AppUserAvatarImageService images;
    @MockBean private AppUserProfileService profileService;
    @MockBean private AppUserProfileMapper mapper;
    @MockBean private ObjectStorageService storage;
    @MockBean private AuditLogService auditLogService;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @TestConfiguration
    static class Images {
        @Bean
        AppUserAvatarImageService images(AppUserProfileMapper mapper, ObjectStorageService storage) {
            var properties = new StorageProperties();
            properties.setPublicMediaOrigin("https://avatar-test.example");
            properties.setSecretKey("test-only-avatar-signing-secret");
            return new AppUserAvatarImageService(mapper, storage, properties);
        }
    }

    @BeforeEach
    void passThroughAuthenticationFilters() throws Exception {
        doAnswer(invocation -> {
            invocation.getArgument(2, jakarta.servlet.FilterChain.class)
                    .doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            invocation.getArgument(2, jakarta.servlet.FilterChain.class)
                    .doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(adminRbacAuthorizationFilter).doFilter(any(), any(), any());
    }

    @Test
    void realSecurityChainAndRealCapabilityAllowOnlyCurrentSignedAnonymousImageGet() throws Exception {
        when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey", KEY));
        when(storage.get(KEY)).thenReturn(new ByteArrayInputStream(PNG));
        mockMvc.perform(get(URI.create(images.issueUrl(42L, KEY))))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG))
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        verifyNoInteractions(profileService);
    }

    @Test
    void unsignedOrTamperedOwnerGetsTheSameGenericErrorWithoutDatabaseProbe() throws Exception {
        URI valid = URI.create(images.issueUrl(42L, KEY));
        mockMvc.perform(get(valid.getPath())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("USER_AVATAR_IMAGE_UNAVAILABLE"));
        mockMvc.perform(get(URI.create(valid.toString().replace("/image/42/", "/image/43/"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("USER_AVATAR_IMAGE_UNAVAILABLE"));
        verifyNoInteractions(mapper, storage, profileService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD"})
    void evenValidImageCapabilityCannotAuthorizeAnotherHttpMethod(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(images.issueUrl(42L, KEY))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, storage, profileService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void authenticatedUserOrAdminCannotUseHeadEvenWithAValidCapability(String subjectType) throws Exception {
        URI valid = URI.create(images.issueUrl(42L, KEY));
        var authentication = new UsernamePasswordAuthenticationToken("42", null, java.util.List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        when(mapper.profile(42L)).thenReturn(Map.of("avatarObjectKey", KEY));
        when(storage.get(KEY)).thenReturn(new ByteArrayInputStream(PNG));
        clearInvocations(images);

        mockMvc.perform(request(HttpMethod.HEAD, valid).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .authentication(authentication)))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(images, mapper, storage, profileService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/app/profile", "/api/app/profile/nickname-candidates",
            "/api/app/profile/avatar", "/api/app/profile/avatar/image/42",
            "/api/app/profile/avatar/image/42/revision/extra",
            "/api/app/profile/avatar/Image/42/revision"})
    void signedImagePermitNeverOpensTheProfileParentOrExtraRoutes(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, storage, profileService);
    }

    @Test
    void anonymousUploadStillRequiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/api/app/profile/avatar").file("file", PNG)
                        .header("Idempotency-Key", "test-only-avatar-key"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, storage, profileService);
    }
}
