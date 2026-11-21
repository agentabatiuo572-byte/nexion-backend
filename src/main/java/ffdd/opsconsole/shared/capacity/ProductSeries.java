package ffdd.opsconsole.shared.capacity;

import java.util.Map;

public final class ProductSeries {
    private static final Map<String, String> KINDS = Map.of(
            "CLOUD_SHARE", "cloud-share", "S1", "stellarbox-s1",
            "PRO", "stellarbox-pro", "PRO_V2", "stellarbox-pro-v2",
            "RACK_P1", "stellarrack-p1", "RACK_P2", "stellarrack-p2");

    private ProductSeries() {}

    public static java.util.Collection<String> representativeSkus() {
        return KINDS.values();
    }

    public static boolean valid(String seriesCode) {
        return seriesCode != null && KINDS.containsKey(seriesCode);
    }

    public static String kind(String seriesCode, String deviceType) {
        if (valid(seriesCode)) return KINDS.get(seriesCode);
        return switch (deviceType == null ? "" : deviceType.toUpperCase(java.util.Locale.ROOT)) {
            case "PHONE", "MOBILE" -> "phone";
            case "PC", "PC_GPU", "GPU", "PC-GPU" -> "pc-gpu";
            default -> null;
        };
    }
}
