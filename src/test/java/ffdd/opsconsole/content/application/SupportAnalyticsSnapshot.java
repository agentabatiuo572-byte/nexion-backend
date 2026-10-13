package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** Private test snapshots: full-value hashes for every table, raw values only for consumers. */
final class SupportAnalyticsSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String DIGEST = "SORTED_ROW_SHA256_V1";
    static final Set<String> SHARED_ROWS = Set.of("nx_admin_role", "nx_admin_role_permission", "nx_support_rules", "nx_support_activity_coverage");
    static final Set<String> HISTORICAL_ROWS = Set.of("nx_admin_permission", "nx_admin_role_relation", "nx_admin_role_menu", "nx_support_agent_profile", "nx_support_agent_user_assignment");
    static final Map<String, List<String>> OBJECT_COLUMNS = Map.of(
            "nx_admin", List.of("id", "username"), "nx_user", List.of("id"),
            "nx_support_admin_avatar_asset", List.of("id"), "nx_support_attachment", List.of("id"),
            "nx_support_attachment_command", List.of("operation", "actor_type", "actor_id", "command_key"),
            "nx_support_bulk_job", List.of("id"), "nx_admin_account_state", List.of("id"));
    record Capture(Map<String, Object> summary, List<Map<String, String>> objectRows) {}
    private SupportAnalyticsSnapshot() {}

    static Capture capture(JdbcTemplate jdbc, String table) {
        require(table.matches("[A-Za-z0-9_]+"), "Unrecognized snapshot table");
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT * FROM `" + table + "`", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            // MySQL streams rows instead of materializing large payload tables in the driver.
            statement.setFetchSize(Integer.MIN_VALUE);
            return statement;
        }, (org.springframework.jdbc.core.ResultSetExtractor<Capture>) rs -> capture(rs, table));
    }

    static Capture capture(ResultSet rs, String table) throws java.sql.SQLException {
        try {
            var columns = new ArrayList<String>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) columns.add(rs.getMetaData().getColumnName(i));
            var objectColumns = OBJECT_COLUMNS.getOrDefault(table, List.of());
            require(columns.containsAll(objectColumns), "Required object baseline columns missing: " + table);
            var hashes = new ArrayList<String>();
            var rows = new ArrayList<Map<String, String>>();
            var objectRows = new ArrayList<Map<String, String>>();
            while (rs.next()) {
                var row = new TreeMap<String, String>();
                for (int i = 1; i <= columns.size(); i++) row.put(columns.get(i - 1), rs.getString(i));
                hashes.add(rowHash(row));
                if (SHARED_ROWS.contains(table)) rows.add(row);
                if (!objectColumns.isEmpty()) {
                    var projected = new LinkedHashMap<String, String>();
                    for (String column : objectColumns) projected.put(column, row.get(column));
                    objectRows.add(projected);
                }
            }
            Collections.sort(hashes);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("columns", columns); summary.put("rowCount", (long) hashes.size());
            summary.put("digestAlgorithm", DIGEST); summary.put("sha256", aggregate(hashes));
            if (SHARED_ROWS.contains(table)) { rows.sort(java.util.Comparator.comparing(Object::toString)); summary.put("rows", rows); }
            if (HISTORICAL_ROWS.contains(table)) summary.put("rowHashes", hashes);
            objectRows.sort(java.util.Comparator.comparing(Object::toString));
            return new Capture(summary, objectRows);
        } catch (java.sql.SQLException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Cannot capture private table summary: " + table, failure); }
    }

    static String rowHash(Object row) throws Exception {
        JsonNode node = JSON.valueToTree(row);
        var sorted = new TreeMap<String, JsonNode>(); node.fields().forEachRemaining(field -> sorted.put(field.getKey(), field.getValue()));
        var digest = MessageDigest.getInstance("SHA-256");
        try (var output = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) { JSON.writeValue(output, sorted); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String aggregate(List<String> sortedHashes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String hash : sortedHashes) { digest.update(hash.getBytes(StandardCharsets.US_ASCII)); digest.update((byte) '\n'); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void validate(JsonNode snapshot) throws Exception {
        require(snapshot.path("schemaVersion").isIntegralNumber() && snapshot.path("schemaVersion").canConvertToInt(), "Snapshot format missing or out of range");
        int version = snapshot.path("schemaVersion").intValue();
        require(version == 1 || version == 2, "Unsupported shared snapshot format");
        require(snapshot.path("tables").isObject(), "Snapshot tables missing");
        var tables = snapshot.path("tables").fields();
        while (tables.hasNext()) {
            var table = tables.next(); var value = table.getValue();
            if (version == 1) { require(value.path("rows").isArray(), "Legacy snapshot rows missing"); continue; }
            require(value.path("columns").isArray() && !value.path("columns").isEmpty() && value.path("rowCount").isIntegralNumber() && value.path("rowCount").canConvertToLong() && value.path("rowCount").longValue() >= 0
                    && DIGEST.equals(value.path("digestAlgorithm").asText()) && value.path("sha256").asText().matches("[a-f0-9]{64}"), "Invalid table summary: " + table.getKey());
            var columns = new java.util.HashSet<String>();
            for (JsonNode column : value.path("columns")) require(column.isTextual() && columns.add(column.asText()), "Invalid snapshot columns");
            if (SHARED_ROWS.contains(table.getKey())) require(value.path("rows").isArray(), "Required restoration rows missing");
            if (HISTORICAL_ROWS.contains(table.getKey())) require(value.path("rowHashes").isArray(), "Required historical row hashes missing");
            List<String> hashes = new ArrayList<>();
            if (value.has("rows")) {
                require(value.path("rows").isArray(), "Invalid restoration rows");
                for (JsonNode row : value.path("rows")) {
                    var names = new java.util.HashSet<String>(); row.fieldNames().forEachRemaining(names::add);
                    require(row.isObject() && names.equals(columns), "Snapshot raw row columns changed"); hashes.add(rowHash(row));
                }
                Collections.sort(hashes);
            } else if (value.has("rowHashes")) {
                for (JsonNode hash : value.path("rowHashes")) { require(hash.isTextual() && hash.asText().matches("[a-f0-9]{64}"), "Invalid row hash"); hashes.add(hash.asText()); }
                var sorted = new ArrayList<>(hashes); Collections.sort(sorted); require(sorted.equals(hashes), "Historical hashes are not sorted");
            } else continue;
            require(hashes.size() == value.path("rowCount").longValue() && aggregate(hashes).equals(value.path("sha256").asText()), "Snapshot rows disagree with summary");
        }
    }
    static JsonNode requiredRows(JsonNode snapshot, String table) throws Exception {
        validate(snapshot); JsonNode rows = snapshot.path("tables").path(table).path("rows");
        require(rows.isArray(), "Missing required original rows: " + table); return rows;
    }
    static void requireHistoricalRows(JsonNode baseline, String table, List<JsonNode> actual) throws Exception {
        validate(baseline);
        var hashes = new java.util.HashMap<String, Integer>();
        for (JsonNode row : actual) hashes.merge(rowHash(row), 1, Integer::sum);
        var originals = new ArrayList<String>();
        if (baseline.path("schemaVersion").intValue() == 1) for (JsonNode row : requiredRows(baseline, table)) originals.add(rowHash(row));
        else {
            JsonNode stored = baseline.path("tables").path(table).path("rowHashes");
            require(stored.isArray(), "Missing historical table: " + table); for (JsonNode hash : stored) originals.add(hash.asText());
            var expectedColumns = baseline.path("tables").path(table).path("columns");
            for (JsonNode row : actual) { var columns = new java.util.HashSet<String>(); row.fieldNames().forEachRemaining(columns::add); var expected = new java.util.HashSet<String>(); expectedColumns.forEach(column -> expected.add(column.asText())); require(columns.equals(expected), "Historical table columns changed"); }
        }
        for (String hash : originals) { int remaining = hashes.getOrDefault(hash, 0); require(remaining > 0, "Historical shared business row changed: " + table); hashes.put(hash, remaining - 1); }
    }
    static String fileHash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; int read; while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
