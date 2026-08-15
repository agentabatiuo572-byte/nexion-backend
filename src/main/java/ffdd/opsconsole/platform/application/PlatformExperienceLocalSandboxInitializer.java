package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Explicit local-sandbox fixture only. The default/production profiles never
 * import this bean and therefore never receive mock installer metadata.
 */
@Component
@Profile("local-sandbox")
@RequiredArgsConstructor
public class PlatformExperienceLocalSandboxInitializer implements ApplicationRunner {
    private static final String MOCK_URL = "http://127.0.0.1:8110/local-sandbox/app/NexGrid-sandbox.apk";
    private final PlatformConfigFacade config;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        boolean anyConfigured = config.activeValue(PlatformExperienceConfigService.VERSION_KEY).isPresent()
                || config.activeValue(PlatformExperienceConfigService.BASE_URL_KEY).isPresent()
                || config.activeValue(PlatformExperienceConfigService.CHANNELS_KEY).isPresent()
                || config.activeValue(PlatformExperienceConfigService.APP_DOWNLOAD_KEY).isPresent();
        if (anyConfigured) return;
        try {
            config.upsertAdminValue(PlatformExperienceConfigService.VERSION_KEY, "1", "NUMBER",
                    "platform_app_experience", "local-sandbox fixture version");
            config.upsertAdminValue(PlatformExperienceConfigService.BASE_URL_KEY, "https://nexgrid.ai/ref/",
                    "STRING", "platform_app_experience", "local-sandbox fixture share base");
            config.upsertAdminValue(PlatformExperienceConfigService.CHANNELS_KEY, objectMapper.writeValueAsString(List.of(
                    Map.of("key", "zalo", "intentType", "scheme", "textTemplate", "Join {link}", "enabled", true),
                    Map.of("key", "copy", "intentType", "copy", "textTemplate", "Join {link}", "enabled", true),
                    Map.of("key", "poster", "intentType", "poster", "textTemplate", "Join {link}", "enabled", true))),
                    "JSON", "platform_app_experience", "local-sandbox fixture share channels");
            config.upsertAdminValue(PlatformExperienceConfigService.APP_DOWNLOAD_KEY, objectMapper.writeValueAsString(Map.of(
                    "officialUrl", MOCK_URL,
                    "iosUrl", "",
                    "androidUrl", "",
                    "apkUrl", MOCK_URL,
                    "version", "0.0.0-sandbox",
                    "releaseNotes", Map.of("zh", "本地沙箱占位资源", "en", "Local sandbox placeholder resource"),
                    "source", "mock")), "JSON", "platform_app_experience",
                    "local-sandbox fixture installer metadata source=mock");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("PLATFORM_EXPERIENCE_SANDBOX_FIXTURE_SERIALIZATION_FAILED", ex);
        }
    }
}
