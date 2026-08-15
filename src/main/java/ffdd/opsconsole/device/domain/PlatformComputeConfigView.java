package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;

/** Public, server-canonical E6 projection consumed by App/H5 before login. */
public record PlatformComputeConfigView(
        FeatureFlags featureFlags,
        Map<String, Object> publicStats,
        OnlineBonus onlineBonus,
        ComputeConfigView computerCompute,
        ShareConfig share,
        String updatedAt) {
    public record FeatureFlags(
            boolean computeShareEnabled,
            boolean homeNewcomerTasksEnabled,
            boolean homeWeeklyPromoEnabled) {
    }

    public record OnlineBonus(BigDecimal h5BaseFactor, BigDecimal continuityFullHours) {
    }

    public record ShareConfig(String baseUrl, List<ShareChannel> channels, AppDownload appDownload) {
    }

    public record ShareChannel(
            String key,
            String intentType,
            String textTemplate,
            String urlTemplate,
            String androidPackage,
            String iosScheme,
            boolean enabled) {
    }

    public record AppDownload(
            String officialUrl,
            String iosUrl,
            String androidUrl,
            String apkUrl,
            String version,
            Map<String, String> releaseNotes,
            String source) {
    }
}
