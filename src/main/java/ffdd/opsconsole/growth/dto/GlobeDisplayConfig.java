package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** H9 presentation parameters; never used for task dispatch, device telemetry or money. */
public record GlobeDisplayConfig(Integer intervalMs, BigDecimal nodeJitterPct,
        BigDecimal activeJobsMinPct, BigDecimal activeJobsMaxPct,
        BigDecimal throughputMin, BigDecimal throughputMax, Integer latencyJitterMs,
        List<Center> centers) {
    public record Center(String id, Long nodes, Integer latencyMs) {}
    public static final Set<String> CENTER_IDS = Set.of("sg", "jp", "vn", "uk", "fr", "de", "us-ny", "us-sj");

    public boolean valid() {
        if (intervalMs == null || intervalMs < 1000 || intervalMs > 86400000
                || !between(nodeJitterPct, "0", "100")
                || !between(activeJobsMinPct, "0", "100") || !between(activeJobsMaxPct, "0", "100")
                || activeJobsMinPct.compareTo(activeJobsMaxPct) > 0
                || !between(throughputMin, "0", "10000") || !between(throughputMax, "0", "10000")
                || throughputMin.compareTo(throughputMax) > 0
                || latencyJitterMs == null || latencyJitterMs < 0 || latencyJitterMs > 60000
                || centers == null || centers.size() != CENTER_IDS.size()) return false;
        var ids = new java.util.HashSet<String>();
        for (Center center : centers) {
            if (center == null || center.id() == null || !CENTER_IDS.contains(center.id()) || !ids.add(center.id())
                    || center.nodes() == null || center.nodes() < 0 || center.nodes() > 100000000
                    || center.latencyMs() == null || center.latencyMs() < 0 || center.latencyMs() > 60000) return false;
        }
        return true;
    }

    private static boolean between(BigDecimal value, String min, String max) {
        return value != null && value.scale() <= 6
                && value.compareTo(new BigDecimal(min)) >= 0 && value.compareTo(new BigDecimal(max)) <= 0;
    }

    /** Existing visual baseline moves here so clients no longer supply operating numbers. */
    public static GlobeDisplayConfig defaults() {
        return new GlobeDisplayConfig(5400, new BigDecimal("0.4"), new BigDecimal("8"), new BigDecimal("14"),
                new BigDecimal("2.2"), new BigDecimal("3.8"), 6, List.of(
                new Center("sg", 236000L, 18), new Center("jp", 253000L, 22),
                new Center("vn", 163000L, 26), new Center("uk", 249000L, 16),
                new Center("fr", 208000L, 20), new Center("de", 217000L, 14),
                new Center("us-ny", 316000L, 24), new Center("us-sj", 284000L, 17)));
    }
}
