package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.util.Map;

/** Public, server-canonical E6 projection consumed by App/H5 before login. */
public record PlatformComputeConfigView(
        FeatureFlags featureFlags,
        Map<String, Object> publicStats,
        OnlineBonus onlineBonus,
        ComputeConfigView computerCompute,
        String updatedAt) {
    public record FeatureFlags(boolean computeShareEnabled) {
    }

    public record OnlineBonus(BigDecimal h5BaseFactor, BigDecimal continuityFullHours) {
    }
}
