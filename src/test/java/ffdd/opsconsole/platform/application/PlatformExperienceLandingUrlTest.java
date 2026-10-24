package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigUpdateRequest.AppDownloadRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlatformExperienceLandingUrlTest {
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlatformExperienceConfigService service = new PlatformExperienceConfigService(config, mapper, null, null, null);

    private PlatformExperienceConfigUpdateRequest request(String official, String android) {
        return new PlatformExperienceConfigUpdateRequest(10L, "https://app.uvel.ai/ref/", List.of(),
                new AppDownloadRequest(official, "", android, "", "1.0.0", Map.of("zh", "正式发布", "en", "Release"), "official"),
                "将链接定位到官网下载区域", "reviewer");
    }

    @Test
    void validatesAndReadsBackOfficialLandingFragment() throws Exception {
        var request = request("https://uvel.ai/#download", "https://app.uvel.ai/app.apk");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "validate", request)).isNull();
        when(config.activeValue(PlatformExperienceConfigService.BASE_URL_KEY)).thenReturn(Optional.of(request.baseUrl()));
        when(config.activeValue(PlatformExperienceConfigService.CHANNELS_KEY)).thenReturn(Optional.of("[]"));
        when(config.activeValue(PlatformExperienceConfigService.APP_DOWNLOAD_KEY)).thenReturn(Optional.of(mapper.writeValueAsString(request.appDownload())));
        assertThat(service.publicConfig(true).getData().appDownload().officialUrl()).isEqualTo("https://uvel.ai/#download");
        assertThat(service.publicConfig(false).getData().appDownload().officialUrl()).isEqualTo("https://uvel.ai/");
        assertThat(service.publicConfig(false).getData().appDownload().androidUrl()).isEqualTo("https://app.uvel.ai/app.apk");
        assertThat(service.publicConfig(true).getData().appDownload().officialUrl()).isEqualTo("https://uvel.ai/#download");
    }

    @Test
    void unavailableConfigWithMissingOfficialUrlRemainsReadableForBothClients() {
        when(config.activeValue(PlatformExperienceConfigService.BASE_URL_KEY)).thenReturn(Optional.of(""));
        when(config.activeValue(PlatformExperienceConfigService.CHANNELS_KEY)).thenReturn(Optional.of("[]"));
        when(config.activeValue(PlatformExperienceConfigService.APP_DOWNLOAD_KEY)).thenReturn(Optional.of("{\"source\":\"unavailable\"}"));
        assertThat(service.publicConfig(false).getData().appDownload().source()).isEqualTo("unavailable");
        assertThat(service.publicConfig(true).getData().appDownload().source()).isEqualTo("unavailable");
    }

    @Test
    void rejectsUnsafeOfficialUrlsAndStillRejectsFragmentsOnPackageUrls() {
        for (String url : List.of("javascript:alert(1)#download", "http://uvel.ai/#download", "https://user:pass@uvel.ai/#download", "https://uvel.ai/#bad anchor")) {
            assertThat((String) ReflectionTestUtils.invokeMethod(service, "validate", request(url, "")))
                    .isEqualTo("PLATFORM_EXPERIENCE_OFFICIAL_URL_INVALID");
        }
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "validate", request("https://uvel.ai/#download", "https://uvel.ai/app.apk#download")))
                .isEqualTo("PLATFORM_EXPERIENCE_ANDROID_URL_INVALID");
    }
}
