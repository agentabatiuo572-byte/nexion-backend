package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import ffdd.opsconsole.team.mapper.LeaderboardPauseSnapshotMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LeaderboardPauseSnapshotService {
    public static final String POINTER_KEY = "team.runtime.F.leaderboard.activePauseSnapshot";
    private static final List<String> PERIODS = List.of("today", "week", "month", "all");
    private final LeaderboardPauseSnapshotMapper mapper;
    private final PlatformConfigFacade config;
    private final ObjectMapper json;

    /** Caller already owns R04 maintenance -4 and current pause row locks, in A2's RC transaction. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public State capture() {
        String operation = A2ReplayContext.operationId();
        if (!A2ReplayContext.isReplaying() || operation == null || operation.isBlank()) {
            throw new BizException(409, "A2_CONFIRMATION_REQUIRED");
        }
        Instant at = Instant.now();
        Map<String, Object> parameters = captureParameters(at);
        // A minimum removes only the tail of a descending ranking. Capture the original
        // bounded queries at minimum zero, then apply the same minimum from this statement's
        // config row. This preserves ranks/ties/topN and avoids a separate config read view.
        List<LeaderboardPauseSnapshotMapper.CaptureRow> facts = mapper.capture(parameters);
        if (facts == null) throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_CAPTURE_FAILED");
        ObjectNode envelope = json.createObjectNode();
        envelope.put("schemaVersion", 1).put("sourceEnvironment", "PRODUCTION")
                .put("pauseOperationId", operation).put("capturedAt", at.toString());
        ObjectNode settings = null, summary = null;
        List<ObjectNode> podium = new ArrayList<>();
        Map<String, List<ObjectNode>> periods = new LinkedHashMap<>();
        PERIODS.forEach(period -> periods.put(period, new ArrayList<>()));
        for (var fact : facts) {
            ObjectNode value = object(fact.payload());
            switch (fact.kind()) {
                case "config" -> { requireCapture(configInputs(value)); settings = value; }
                case "pcSummary" -> { requireCapture(pcSummaryValid(value)); summary = value; }
                case "pcPodium" -> { requireCapture(pcRowValid(value) && money(value.get("volumeUsd"), true)); podium.add(value); }
                default -> {
                    if (!periods.containsKey(fact.kind())) throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_CAPTURE_FAILED");
                    requireCapture(appRowValid(value, cap(fact.kind())));
                    periods.get(fact.kind()).add(value);
                }
            }
        }
        if (settings == null || summary == null) throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_CAPTURE_FAILED");
        BigDecimal pcMinimum = minimum(settings.path("minUsd").asText(""), false);
        BigDecimal appMinimum = minimum(settings.path("minUsd").asText(""), true);
        podium.removeIf(row -> row.path("volumeUsd").decimalValue().compareTo(pcMinimum) < 0);
        podium.sort(Comparator.comparingInt(row -> row.path("rank").asInt()));
        podium.forEach(row -> row.remove("volumeUsd"));
        ObjectNode pc = envelope.putObject("pcProjection");
        pc.put("basis", "PC_GV_AND_MONTH_LEADERSHIP_V1");
        pc.set("summary", summary); pc.set("podium", json.valueToTree(podium));
        ObjectNode app = envelope.putObject("appPeriods");
        for (String period : PERIODS) {
            List<ObjectNode> rows = periods.get(period);
            rows.removeIf(row -> row.path("earnedUsdt").decimalValue().compareTo(appMinimum) < 0);
            rows.sort(Comparator.comparingInt(row -> row.path("rank").asInt()));
            ObjectNode projection = app.putObject(period);
            projection.put("basis", "APP_UNLOCKED_COMMISSIONS_V1").put("snapshotAt", at.toString());
            BigDecimal pool = prize(settings, period);
            projection.put("poolUsd", pool).put("topN", pool.signum() > 0 ? rows.size() : 0);
            projection.set("rows", json.valueToTree(rows));
        }
        envelope.set("configInputs", settings);
        requireCapture(projectionsValid(envelope));
        String hash = hash(envelope);
        envelope.put("contentHash", hash);
        String key = "pause-" + digest(operation);
        if (mapper.insert(key, write(envelope)) != 1) throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_CAPTURE_FAILED");
        config.upsertAdminValue(POINTER_KEY, write(Map.of("key", key, "hash", hash, "schemaVersion", 1)),
                "JSON", "team_runtime", "F4 current immutable display pause snapshot");
        return new State(true, "FROZEN", "", key, at.toString(), hash, envelope);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resume() {
        config.upsertAdminValue(POINTER_KEY, "", "JSON", "team_runtime", "F4 resumed display; history retained");
    }

    /** Pause/ref/envelope are selected in one statement; no GET writes or legacy reconstruction. */
    public State current() {
        var row = mapper.current();
        if (row == null) throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_READ_FAILED");
        if (!paused(row.pausedValue())) return State.live();
        if (row.snapshotRef() == null || row.snapshotRef().isBlank()) return State.unavailable("PRE_SNAPSHOT_PAUSE");
        try {
            ObjectNode ref = object(row.snapshotRef());
            if (row.envelope() == null || row.storedKey() == null) return State.unavailable("SNAPSHOT_MISSING");
            ObjectNode envelope = object(row.envelope());
            if (!integer(ref.get("schemaVersion"), 1, 1) || !integer(envelope.get("schemaVersion"), 1, 1)) {
                return State.unavailable("SNAPSHOT_SCHEMA_UNSUPPORTED");
            }
            String expected = ref.path("hash").asText();
            if (!row.storedKey().equals(ref.path("key").asText()) || !expected.matches("[a-f0-9]{64}")
                    || !expected.equals(envelope.path("contentHash").asText()) || !expected.equals(hash(envelope))
                    || !"PRODUCTION".equals(envelope.path("sourceEnvironment").asText())
                    || envelope.path("pauseOperationId").asText().isBlank()
                    || !row.storedKey().equals("pause-" + digest(envelope.path("pauseOperationId").asText()))
                    || !projectionsValid(envelope)) {
                return State.unavailable("SNAPSHOT_INVALID");
            }
            String at = envelope.path("capturedAt").asText(); Instant.parse(at);
            return new State(true, "FROZEN", "", row.storedKey(), at, expected, envelope);
        } catch (RuntimeException ex) {
            return State.unavailable("SNAPSHOT_INVALID");
        }
    }

    public Map<String, Object> pcSummary(State state) {
        if (!"FROZEN".equals(state.mode())) return Map.of();
        JsonNode row = state.envelope().path("pcProjection").path("summary");
        return Map.of("poolUsd", row.path("poolUsd").decimalValue(), "participantCount", row.path("participantCount").asInt());
    }

    public List<Map<String, Object>> pcPodium(State state) {
        if (!"FROZEN".equals(state.mode())) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        state.envelope().path("pcProjection").path("podium").forEach(row -> {
            Map<String, Object> value = json.convertValue(row, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            value.put("tip", "冻结时 · " + value.getOrDefault("tip", ""));
            rows.add(value);
        });
        return rows;
    }

    static Map<String, Object> captureParameters(Instant at) {
        LocalDate today = at.atZone(DateTimeFormatConfig.BUSINESS_ZONE).toLocalDate();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("snapshotAt", LocalDateTime.ofInstant(at, DateTimeFormatConfig.BUSINESS_ZONE));
        p.put("sandbox", 0); p.put("minVolumeUsd", BigDecimal.ZERO); p.put("pcLimit", 3);
        for (String period : PERIODS) {
            p.put(period + "ActionPeriod", "all".equals(period) ? "allTime" : period);
            p.put(period + "Limit", "today".equals(period) ? 20 : "week".equals(period) ? 50 : 100);
            p.put(period + "FromInclusive", switch (period) {
                case "today" -> today.atStartOfDay(); case "week" -> monday.atStartOfDay();
                case "month" -> today.withDayOfMonth(1).atStartOfDay(); default -> null;
            });
            p.put(period + "ToExclusive", switch (period) {
                case "today" -> today.plusDays(1).atStartOfDay(); case "week" -> monday.plusWeeks(1).atStartOfDay();
                case "month" -> today.withDayOfMonth(1).plusMonths(1).atStartOfDay(); default -> null;
            });
        }
        return p;
    }

    static boolean paused(String raw) {
        return raw != null && List.of("on", "true", "1", "paused").contains(raw.trim().toLowerCase(java.util.Locale.ROOT));
    }
    private static BigDecimal minimum(String raw, boolean app) {
        try { return new BigDecimal((app ? raw.replace("$", "").replace(",", "") : raw).trim()).max(BigDecimal.ZERO); }
        catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }
    private static BigDecimal prize(JsonNode settings, String period) {
        BigDecimal override = minimum(settings.path("weekPoolUsd").asText(""), true);
        if ("week".equals(period) && override.signum() > 0) return override;
        String field = "all".equals(period) ? "allTime" : period;
        var match = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                .matcher(settings.path("periodPrize").asText(""));
        return match.find() ? new BigDecimal(match.group(1)) : BigDecimal.ZERO;
    }
    private ObjectNode object(String value) {
        try {
            JsonNode node = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);
            if (!node.isObject()) throw new IllegalArgumentException("object required");
            return (ObjectNode) node;
        } catch (Exception ex) { throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_INVALID"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_INVALID"); }
    }
    private String hash(ObjectNode envelope) { return digest(write(canonical(envelope))); }
    private Object canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            value.fields().forEachRemaining(entry -> {
                if (!"contentHash".equals(entry.getKey())) sorted.put(entry.getKey(), canonical(entry.getValue()));
            });
            return sorted;
        }
        if (value.isArray()) { List<Object> rows = new ArrayList<>(); value.forEach(item -> rows.add(canonical(item))); return rows; }
        // Keep a JSON number a number; text "120" must never hash as numeric 120.
        if (value.isNumber()) return value.decimalValue().stripTrailingZeros();
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        return value.textValue();
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static int cap(String period) { return "today".equals(period) ? 20 : "week".equals(period) ? 50 : 100; }
    private static boolean text(JsonNode n) { return n != null && n.isTextual(); }
    private static boolean nullableText(JsonNode n) { return n != null && (n.isNull() || n.isTextual()); }
    private static boolean integer(JsonNode n, long min, long max) {
        if (n == null || !n.isNumber()) return false;
        BigDecimal value = n.decimalValue();
        return value.stripTrailingZeros().scale() <= 0 && value.compareTo(BigDecimal.valueOf(min)) >= 0
                && value.compareTo(BigDecimal.valueOf(max)) <= 0;
    }
    private static boolean money(JsonNode n, boolean nonnegative) {
        return n != null && n.isNumber() && (!nonnegative || n.decimalValue().signum() >= 0);
    }
    private static boolean configInputs(JsonNode n) {
        return n.isObject() && nullableText(n.get("minUsd")) && nullableText(n.get("weekPoolUsd")) && nullableText(n.get("periodPrize"));
    }
    private static boolean pcSummaryValid(JsonNode n) {
        return n.isObject() && money(n.get("poolUsd"), false) && integer(n.get("participantCount"), 0, Integer.MAX_VALUE)
                && integer(n.get("fraudHitCount"), 0, Integer.MAX_VALUE) && text(n.get("periodStatus"));
    }
    private static boolean pcRowValid(JsonNode n) {
        return n.isObject() && integer(n.get("rank"), 1, 3) && integer(n.get("memberUserId"), 1, Long.MAX_VALUE)
                && text(n.get("userId")) && !n.get("userId").asText().isBlank()
                && text(n.get("gmvLabel")) && n.get("gmvLabel").asText().matches("\\$-?\\d+(?:\\.\\d+)?")
                && text(n.get("tip")) && text(n.get("className"));
    }
    private static boolean appRowValid(JsonNode n, int maxRank) {
        return n.isObject() && integer(n.get("rank"), 1, maxRank) && integer(n.get("userId"), 1, Long.MAX_VALUE)
                && nullableText(n.get("nickname")) && nullableText(n.get("vRank")) && money(n.get("earnedUsdt"), true)
                && integer(n.get("directs"), 0, Integer.MAX_VALUE) && integer(n.get("teamSize"), 0, Integer.MAX_VALUE)
                && integer(n.get("hasDevice"), 0, 1);
    }
    private static boolean projectionsValid(JsonNode envelope) {
        try {
            JsonNode pc = envelope.path("pcProjection");
            if (!"PC_GV_AND_MONTH_LEADERSHIP_V1".equals(pc.path("basis").asText())
                    || !pcSummaryValid(pc.path("summary")) || !pc.path("podium").isArray()
                    || !configInputs(envelope.path("configInputs"))) return false;
            for (JsonNode row : pc.path("podium")) if (!pcRowValid(row)) return false;
            String at = envelope.path("capturedAt").asText(); Instant.parse(at);
            for (String period : PERIODS) {
                JsonNode projection = envelope.path("appPeriods").path(period);
                if (!"APP_UNLOCKED_COMMISSIONS_V1".equals(projection.path("basis").asText())
                        || !at.equals(projection.path("snapshotAt").asText()) || !money(projection.get("poolUsd"), true)
                        || !integer(projection.get("topN"), 0, cap(period)) || !projection.path("rows").isArray()
                        || projection.path("rows").size() > cap(period)) return false;
                int expectedRank = 1;
                for (JsonNode row : projection.path("rows")) {
                    if (!appRowValid(row, cap(period)) || row.path("rank").intValue() != expectedRank++) return false;
                }
            }
            return true;
        } catch (RuntimeException malformed) { return false; }
    }
    private static void requireCapture(boolean valid) {
        if (!valid) throw new BizException(503, "F4_LEADERBOARD_SNAPSHOT_CAPTURE_FAILED");
    }

    public record State(boolean paused, String mode, String reason, String id, String capturedAt, String version, JsonNode envelope) {
        static State live() { return new State(false, "LIVE", "", "", "", "", null); }
        static State unavailable(String reason) { return new State(true, "UNAVAILABLE", reason, "", "", "", null); }
        public boolean available() { return !"UNAVAILABLE".equals(mode); }
        public void metadata(Map<String, Object> result, boolean pc) {
            result.put(pc ? "leaderboardPaused" : "paused", paused);
            result.put(pc ? "leaderboardSnapshotState" : "snapshotState", mode);
            result.put(pc ? "leaderboardDataAvailable" : "dataAvailable", available());
            result.put(pc ? "leaderboardSnapshotId" : "pauseSnapshotId", id);
            result.put(pc ? "leaderboardSnapshotAt" : "pauseCapturedAt", capturedAt);
            result.put(pc ? "leaderboardSnapshotUnavailableReason" : "snapshotUnavailableReason", reason);
        }
        public List<AppTeamInsightsMapper.LeaderboardRow> appRows(String period, ObjectMapper json) {
            List<AppTeamInsightsMapper.LeaderboardRow> rows = new ArrayList<>();
            envelope.path("appPeriods").path(period).path("rows").forEach(row -> rows.add(json.convertValue(row, AppTeamInsightsMapper.LeaderboardRow.class)));
            return List.copyOf(rows);
        }
    }
}
