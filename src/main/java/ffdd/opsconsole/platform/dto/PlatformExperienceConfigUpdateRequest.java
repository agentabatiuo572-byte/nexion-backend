package ffdd.opsconsole.platform.dto;

import java.util.List;
import java.util.Map;

/** A3 command for the App/H5 experience projection; home flags remain H3-owned read-only facts. */
public record PlatformExperienceConfigUpdateRequest(
        Long expectedVersion,
        String baseUrl,
        List<ShareChannelRequest> channels,
        AppDownloadRequest appDownload,
        String reason,
        String operator) {
    public record ShareChannelRequest(
            String key,
            String intentType,
            String textTemplate,
            String urlTemplate,
            String androidPackage,
            String iosScheme,
            Boolean enabled) {
    }

    public record AppDownloadRequest(
            String officialUrl,
            String iosUrl,
            String androidUrl,
            String apkUrl,
            String version,
            Map<String, String> releaseNotes,
            String source) {
    }
}
