package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlatformExperienceConfigContractTest {
    @Test
    void adminApiExposesVersionedExperienceConfigWithDedicatedPermissions() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/ffdd/opsconsole/platform/web/OpsPlatformConfigController.java"));
        assertThat(controller).contains("/experience", "platform_a3_read", "platform_a3_write");
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/platform/application/PlatformExperienceConfigService.java"));
        assertThat(service).contains("expectedVersion", "PLATFORM_EXPERIENCE_VERSION_CONFLICT", "recordRequired");
        String request = Files.readString(Path.of("src/main/java/ffdd/opsconsole/platform/dto/PlatformExperienceConfigUpdateRequest.java"));
        assertThat(request).contains("Long expectedVersion", "String reason", "AppDownloadRequest appDownload");
        String view = Files.readString(Path.of("src/main/java/ffdd/opsconsole/platform/dto/PlatformExperienceConfigView.java"));
        assertThat(view).contains("long version", "boolean ready", "updatedAt");
    }

    @Test
    void experienceContractValidatesSourceUrlAndShareEnumsBeforeProjection() throws Exception {
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/platform/application/PlatformExperienceConfigService.java"));
        assertThat(service).contains("official", "mock", "unavailable", "urlTemplate", "textTemplate");
        String view = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/domain/PlatformComputeConfigView.java"));
        assertThat(view).contains("homeNewcomerTasksEnabled", "homeWeeklyPromoEnabled", "ShareConfig", "releaseNotes");
    }

    @Test
    void localSandboxHasAnExplicitMockInstallerFixtureAndNeverUsesItByDefault() throws Exception {
        String initializer = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/platform/application/PlatformExperienceLocalSandboxInitializer.java"));
        assertThat(initializer).contains("@Profile(\"local-sandbox\")", "source", "mock", "/local-sandbox/app/");
        String mockResource = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/platform/web/LocalSandboxInstallerController.java"));
        assertThat(mockResource).contains("@Profile(\"local-sandbox\")", "/local-sandbox/app/");
    }
}
