package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;

/** Public, server-canonical E6 projection consumed by App/H5 before login. */
public record PlatformComputeConfigView(
        FeatureFlags featureFlags,
        OnlineBonus onlineBonus,
        ComputeConfigView computerCompute,
        String updatedAt) {
    public record FeatureFlags(boolean computeShareEnabled) {
    }

    public record OnlineBonus(BigDecimal h5BaseFactor, BigDecimal continuityFullHours) {
    }
}
