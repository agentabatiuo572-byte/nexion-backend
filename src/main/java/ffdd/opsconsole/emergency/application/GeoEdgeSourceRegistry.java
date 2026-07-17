package ffdd.opsconsole.emergency.application;

import java.util.List;
import java.util.Map;

/** Closed allowlist of country headers that may be supplied by a trusted edge proxy. */
public final class GeoEdgeSourceRegistry {
    public static final String DEFAULT_SOURCE = "nexion-gateway";

    private static final Map<String, Source> SOURCES = Map.of(
            DEFAULT_SOURCE, new Source(DEFAULT_SOURCE, "Nexion 网关", "X-Nexion-Edge-Country"),
            "cloudflare", new Source("cloudflare", "Cloudflare 边缘网络", "CF-IPCountry"));

    private GeoEdgeSourceRegistry() {
    }

    public static boolean supports(String key) {
        return key != null && SOURCES.containsKey(key.trim().toLowerCase());
    }

    public static String headerName(String key) {
        Source source = SOURCES.get(normalize(key));
        return source == null ? null : source.headerName();
    }

    public static java.util.Set<String> keys() {
        return java.util.Set.copyOf(SOURCES.keySet());
    }

    public static List<Map<String, Object>> options(GeoEdgeHealthMonitor healthMonitor) {
        return SOURCES.values().stream()
                .sorted(java.util.Comparator.comparing(Source::key))
                .map(source -> {
                    GeoEdgeHealthMonitor.Snapshot health = healthMonitor.snapshot(source.key());
                    return Map.<String, Object>of(
                            "value", source.key(),
                            "label", source.label(),
                            "healthy", health.healthy(),
                            "healthStatus", health.status(),
                            "sampleCount", health.sampleCount(),
                            "description", health.healthy()
                                    ? "最近 5 分钟至少 20 个可信请求头样本，判定正常"
                                    : health.sampleCount() == 0
                                    ? "最近 5 分钟尚无足够可信请求头样本"
                                    : "最近 5 分钟请求头样本不足或失败率过高");
                })
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private record Source(String key, String label, String headerName) {
    }
}
