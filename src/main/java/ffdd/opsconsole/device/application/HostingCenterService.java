package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HostingCenterService {
    public static final String OPERATION = "e1_hosting_centers_update";
    public static final String REVIEW_FIELD = "hostingCenterReview";
    private static final String KEY = "E.device.hostingCenters";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Center> DEFAULTS = List.of(
            new Center("sg", "Singapore SG-1", true, 1),
            new Center("jp", "Tokyo JP-1", true, 1),
            new Center("vn", "Ho Chi Minh City VN-1", true, 1),
            new Center("uk", "London UK-1", true, 1),
            new Center("fr", "Paris FR-1", true, 1),
            new Center("de", "Frankfurt DE-1", true, 1),
            new Center("us-ny", "New York US-1", true, 1),
            new Center("us-sj", "San Jose US-2", true, 1));
    private final PlatformConfigFacade config;
    private final AuditLogService audit;

    public record Center(String id, String name, boolean enabled, int weight) {}
    public record State(long revision, List<Center> centers) {}
    public record Review(State before, State after) {}

    public record Pool(List<Center> enabled, long totalWeight) {
        public String assign(String unitKey) {
            // A delivery retry uses the same draw as long as the published pool is the same.
            long seed = UUID.nameUUIDFromBytes(unitKey.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
            long ticket = new SplittableRandom(seed).nextLong(totalWeight);
            for (Center center : enabled) {
                ticket -= center.weight();
                if (ticket < 0) return center.name();
            }
            throw new IllegalStateException("Hosting center weights do not match the pool");
        }
    }

    public State read() {
        return parse(config.activeValue(KEY).orElse(null));
    }

    public Pool requirePool() {
        List<Center> enabled = read().centers().stream().filter(Center::enabled).toList();
        if (enabled.isEmpty()) throw new BizException(409, "HOSTING_CENTERS_UNAVAILABLE");
        return new Pool(enabled, enabled.stream().mapToLong(Center::weight).sum());
    }

    public Review review(Map<String, Object> params) {
        return candidate(params, read());
    }

    @Transactional(rollbackFor = Exception.class)
    public State apply(Map<String, Object> params, String operator, String reason) {
        if (!A2ReplayContext.isReplaying()) throw new BizException(409, "A2_CONFIRMATION_REQUIRED");
        String raw = config.activeValueForUpdate(KEY).orElse(null);
        Review review = candidate(params, parse(raw));
        String value = encode(review.after());
        if (raw != null) {
            config.upsertAdminValue(KEY, value, "JSON", "E1", "托管机房清单");
        } else if (!config.upsertAdminValueIfUnconfigured(KEY, value, "JSON", "E1", "托管机房清单")) {
            throw new BizException(409, "HOSTING_CENTERS_REVISION_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("E1_HOSTING_CENTERS_CHANGED").resourceType("HOSTING_CENTERS").resourceId("default")
                .actorType("ADMIN").actorUsername(operator).method("POST").path("/api/admin/platform/audit/operations")
                .result("SUCCESS").riskLevel("LOW")
                .detail(Map.of("before", review.before(), "after", review.after(), "reason", reason)).build());
        return review.after();
    }

    private Review candidate(Map<String, Object> params, State before) {
        JsonNode input = JSON.valueToTree(params);
        JsonNode expected = input.path("expectedRevision");
        if (input.size() != 2 || !expected.isIntegralNumber() || !expected.canConvertToLong()
                || expected.longValue() < 0) throw new BizException(422, "HOSTING_CENTERS_INVALID");
        if (before.revision() != expected.longValue()) throw new BizException(409, "HOSTING_CENTERS_REVISION_CONFLICT");
        List<Center> centers = centers(input.path("centers"));
        if (centers.stream().noneMatch(Center::enabled)) throw new BizException(422, "HOSTING_CENTERS_ENABLED_REQUIRED");
        return new Review(before, new State(Math.addExact(before.revision(), 1), centers));
    }

    private State parse(String raw) {
        if (raw == null) return new State(0, DEFAULTS);
        try {
            JsonNode state = JSON.readTree(raw);
            if (state == null || !state.isObject()) throw new BizException(409, "HOSTING_CENTERS_UNAVAILABLE");
            JsonNode revision = state.path("revision");
            if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 1
                    || revision.longValue() == Long.MAX_VALUE) throw new BizException(409, "HOSTING_CENTERS_UNAVAILABLE");
            return new State(revision.longValue(), centers(state.path("centers")));
        } catch (JsonProcessingException | BizException invalid) {
            throw new BizException(409, "HOSTING_CENTERS_UNAVAILABLE");
        }
    }

    private List<Center> centers(JsonNode input) {
        if (!input.isArray() || input.size() != DEFAULTS.size()) throw new BizException(422, "HOSTING_CENTERS_INVALID");
        Map<String, Center> parsed = new LinkedHashMap<>();
        for (JsonNode row : input) {
            String id = row.path("id").textValue();
            String name = row.path("name").asText("").strip();
            JsonNode enabled = row.path("enabled");
            JsonNode weight = row.path("weight");
            if (!row.isObject() || row.size() != 4 || DEFAULTS.stream().noneMatch(center -> center.id().equals(id))
                    || !row.path("name").isTextual() || name.isBlank() || name.length() > 128
                    || name.codePoints().anyMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN
                            || Character.isISOControl(ch))
                    || !enabled.isBoolean() || !weight.isIntegralNumber() || !weight.canConvertToInt() || weight.intValue() < 1
                    || parsed.containsKey(id)) {
                throw new BizException(422, "HOSTING_CENTERS_INVALID");
            }
            parsed.put(id, new Center(id, name, enabled.booleanValue(), weight.intValue()));
        }
        return DEFAULTS.stream().map(Center::id).filter(parsed::containsKey).map(parsed::get).toList();
    }

    private String encode(State next) {
        try {
            return JSON.writeValueAsString(next);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize hosting center configuration", failure);
        }
    }
}
