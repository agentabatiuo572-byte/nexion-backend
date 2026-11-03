package ffdd.opsconsole.growth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** H9 presentation parameters; never used for task dispatch, device telemetry or money. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobeDisplayConfig(Integer intervalMs, BigDecimal nodeJitterPct,
        BigDecimal activeJobsMinPct, BigDecimal activeJobsMaxPct,
        BigDecimal throughputMin, BigDecimal throughputMax, Integer latencyJitterMs,
        List<Center> centers) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Center(String id, Long nodes, Integer latencyMs,
            @JsonDeserialize(using = SharePercentage.class) BigDecimal sharePct) {
        public Center(String id, Long nodes, Integer latencyMs) {
            this(id, nodes, latencyMs, null);
        }

        @Override public String toString() {
            // H9 idempotency keys issued by older clients hash this representation.
            return "Center[id=" + id + ", nodes=" + nodes + ", latencyMs=" + latencyMs
                    + (sharePct == null ? "" : ", sharePct=" + sharePct) + "]";
        }
    }
    public static final class SharePercentage extends JsonDeserializer<BigDecimal> {
        @Override public Object getAbsentValue(DeserializationContext context) { return null; }
        @Override public BigDecimal getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(BigDecimal.class, "H9_GLOBE_DISPLAY_INVALID");
        }
        @Override public BigDecimal deserialize(JsonParser input, DeserializationContext context) throws IOException {
            if (!input.currentToken().isNumeric()) return context.reportInputMismatch(BigDecimal.class, "H9_GLOBE_DISPLAY_INVALID");
            return input.getDecimalValue();
        }
    }
    public static final Set<String> CENTER_IDS = Set.of("sg", "jp", "vn", "uk", "fr", "de", "us-ny", "us-sj");

    public boolean valid() {
        if (intervalMs == null || intervalMs < 1000 || intervalMs > 86400000
                || nodeJitterPct != null && !between(nodeJitterPct, "0", "100")
                || activeJobsMinPct != null && !between(activeJobsMinPct, "0", "100")
                || activeJobsMaxPct != null && !between(activeJobsMaxPct, "0", "100")
                || activeJobsMinPct != null && activeJobsMaxPct != null && activeJobsMinPct.compareTo(activeJobsMaxPct) > 0
                || !between(throughputMin, "0", "10000") || !between(throughputMax, "0", "10000")
                || throughputMin.compareTo(throughputMax) > 0
                || latencyJitterMs == null || latencyJitterMs < 0 || latencyJitterMs > 60000
                || centers == null || centers.size() != CENTER_IDS.size()) return false;
        var ids = new java.util.HashSet<String>();
        int shares = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Center center : centers) {
            if (center == null || center.id() == null || !CENTER_IDS.contains(center.id()) || !ids.add(center.id())
                    || center.nodes() == null && center.sharePct() == null
                    || center.nodes() != null && (center.nodes() < 0 || center.nodes() > 100000000)
                    || center.latencyMs() == null || center.latencyMs() < 0 || center.latencyMs() > 60000) return false;
            if (center.sharePct() != null) {
                BigDecimal pct = center.sharePct();
                if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0 || pct.stripTrailingZeros().scale() > 2) return false;
                shares++;
                total = total.add(pct);
            }
        }
        return shares == 0 || (shares == CENTER_IDS.size() && total.compareTo(BigDecimal.valueOf(100)) == 0);
    }

    /** A legacy client can still edit latency and throughput without resetting the published shares. */
    public GlobeDisplayConfig withSavedFields(GlobeDisplayConfig saved) {
        Map<String, Center> byId = saved.centers().stream().collect(Collectors.toMap(Center::id, Function.identity()));
        List<Center> merged = centers.stream().map(center -> {
            Center old = byId.get(center.id());
            return new Center(center.id(), center.nodes() == null ? old.nodes() : center.nodes(), center.latencyMs(),
                    center.sharePct() == null ? old.sharePct() : center.sharePct());
        }).toList();
        return new GlobeDisplayConfig(intervalMs, nodeJitterPct == null ? saved.nodeJitterPct() : nodeJitterPct,
                activeJobsMinPct == null ? saved.activeJobsMinPct() : activeJobsMinPct,
                activeJobsMaxPct == null ? saved.activeJobsMaxPct() : activeJobsMaxPct,
                throughputMin, throughputMax, latencyJitterMs, merged);
    }

    private static boolean between(BigDecimal value, String min, String max) {
        return value != null && value.scale() <= 6
                && value.compareTo(new BigDecimal(min)) >= 0 && value.compareTo(new BigDecimal(max)) <= 0;
    }

    /** Existing visual baseline moves here so clients no longer supply operating numbers. */
    public static GlobeDisplayConfig defaults() {
        return new GlobeDisplayConfig(5400, new BigDecimal("0.4"), new BigDecimal("8"), new BigDecimal("14"),
                new BigDecimal("2.2"), new BigDecimal("3.8"), 6, List.of(
                new Center("sg", 236000L, 18, new BigDecimal("12.25")), new Center("jp", 253000L, 22, new BigDecimal("13.14")),
                new Center("vn", 163000L, 26, new BigDecimal("8.46")), new Center("uk", 249000L, 16, new BigDecimal("12.93")),
                new Center("fr", 208000L, 20, new BigDecimal("10.80")), new Center("de", 217000L, 14, new BigDecimal("11.27")),
                new Center("us-ny", 316000L, 24, new BigDecimal("16.41")), new Center("us-sj", 284000L, 17, new BigDecimal("14.74"))));
    }
}
