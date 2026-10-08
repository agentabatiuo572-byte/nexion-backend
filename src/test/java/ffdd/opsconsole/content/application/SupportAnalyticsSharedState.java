package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Restores only receipt-proven shared role changes; business rule audit history remains intact. */
final class SupportAnalyticsSharedState {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RESTORABLE = Set.of("nx_admin_role", "nx_admin_role_permission");
    private static final Set<String> RULE_AUDIT = Set.of("version", "updated_by", "updated_at", "reason");
    private static final List<String> TABLES = List.of("nx_admin_role", "nx_admin_permission", "nx_admin_role_permission",
            "nx_admin_role_relation", "nx_admin_role_menu", "nx_support_agent_profile", "nx_support_rules",
            "nx_support_activity_coverage", "nx_support_agent_user_assignment");
    private SupportAnalyticsSharedState() {}

    static void verifyAndRestore(Path directory, JdbcTemplate jdbc) throws Exception {
        Path contextPath = directory.resolve("actor-context.json");
        require(hash(contextPath).equals(System.getenv("CS_ENHANCE_ACTOR_CONTEXT_SHA256")), "Cleanup context changed");
        JsonNode context = JSON.readTree(contextPath.toFile());
        SupportExclusiveRuntimeOwnership.requireActual(context, SupportRuntimeTarget.current(), jdbc);
        Path beforePath = Path.of(context.path("phaseSharedBefore").path("path").asText());
        require(hash(beforePath).equals(context.path("phaseSharedBefore").path("sha256").asText()), "Shared baseline changed");
        JsonNode before = JSON.readTree(beforePath.toFile());
        var events = new ArrayList<JsonNode>();
        Path journal = directory.resolve("shared-mutations");
        if (Files.isDirectory(journal)) try (var files = Files.list(journal)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("-COMMITTED.json")).toList()) {
                JsonNode event = JSON.readTree(file.toFile());
                require(event.path("schemaVersion").asInt() == 3 && event.path("commitReturned").asBoolean()
                        && "COMMITTED".equals(event.path("transactionOutcome").asText())
                        && "SHARED_MUTATION_COMMITTED".equals(event.path("event").asText()), "Committed shared receipt required");
                for (String key : List.of("identity", "candidate", "windowId", "resourceIdentity"))
                    require(context.path(key).equals(event.path(key)), "Shared receipt scope changed");
                require(event.path("contextSha256").asText().equals(hash(contextPath)), "Shared receipt context changed");
                Path intentPath = Path.of(event.path("intent").path("path").asText()).toAbsolutePath().normalize();
                require(intentPath.getParent().equals(journal.toAbsolutePath().normalize())
                        && hash(intentPath).equals(event.path("intent").path("sha256").asText()), "Shared intent changed");
                JsonNode intent = JSON.readTree(intentPath.toFile());
                for (String key : List.of("operationId", "identity", "candidate", "windowId", "resourceIdentity", "table", "rowId", "before", "source", "dbStarted"))
                    require(event.path(key).equals(intent.path(key)), "Shared intent/commit mismatch: " + key);
                events.add(event);
            }
        }
        var changes = new ArrayList<Map<String, Object>>();
        var ruleDifferences = new ArrayList<Map<String, Object>>();
        var watermarkDifferences = new ArrayList<Map<String, Object>>();
        for (String table : TABLES) {
            JsonNode originals = before.path("tables").path(table).path("rows");
            require(originals.isArray(), "Missing shared baseline table: " + table);
            List<JsonNode> actual = rows(jdbc, table, false, null);
            if (RESTORABLE.contains(table) || table.equals("nx_support_rules")) {
                for (JsonNode original : originals) {
                    var matches = actual.stream().filter(row -> row.path("id").equals(original.path("id"))).toList();
                    require(matches.size() == 1, "Original shared row disappeared: " + table);
                    JsonNode observed = matches.get(0);
                    List<JsonNode> trace = events.stream().filter(event -> table.equals(event.path("table").asText())
                            && original.path("id").asText().equals(event.path("rowId").asText())).toList();
                    requireChain(original, observed, trace);
                    if (table.equals("nx_support_rules")) {
                        var fields = original.fieldNames();
                        while (fields.hasNext()) {
                            String field = fields.next();
                            if (!original.path(field).equals(observed.path(field))) {
                                require(RULE_AUDIT.contains(field), "Rule business value remains changed: " + field);
                                ruleDifferences.add(Map.of("field", field, "before", original.path(field), "after", observed.path(field)));
                            }
                        }
                    } else if (!original.equals(observed)) changes.add(Map.of("table", table, "before", original, "observed", observed,
                            "operationIds", trace.stream().map(event -> event.path("operationId").asText()).toList()));
                }
            } else if (table.equals("nx_support_activity_coverage")) {
                require(actual.size() == originals.size(), "Coverage record set changed");
                for (JsonNode original : originals) {
                    var matches = actual.stream().filter(row -> row.path("id").equals(original.path("id"))).toList();
                    require(matches.size() == 1, "Original coverage record disappeared");
                    requireCoverageAdvance(original, matches.get(0));
                    if (!original.equals(matches.get(0))) watermarkDifferences.add(Map.of("before", original, "after", matches.get(0),
                            "source", "SupportMaintenanceMapper.advanceWatermark", "rule", "Only observed_through_at may advance; all other fields are unchanged"));
                }
            } else {
                for (JsonNode original : originals) require(actual.contains(original), "Historical shared business row changed: " + table);
            }
        }
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("contextSha256", hash(contextPath)); receipt.put("identity", context.path("identity"));
        receipt.put("changes", changes); receipt.put("ruleAuditDifferences", ruleDifferences);
        receipt.put("watermarkDifferences", watermarkDifferences);
        writeNew(directory.resolve("shared-restore-intent.json"), receipt);
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            for (var change : changes) {
                String table = (String) change.get("table"); JsonNode original = (JsonNode) change.get("before");
                var locked = rows(jdbc, table, true, original.path("id").asText());
                require(locked.size() == 1 && locked.get(0).equals(change.get("observed")), "Shared row moved before restore");
                var setters = new ArrayList<String>(); var values = new ArrayList<Object>();
                original.fields().forEachRemaining(field -> {
                    require(field.getKey().matches("[a-zA-Z0-9_]+"), "Unrecognized shared column");
                    if (!field.getKey().equals("id")) { setters.add("`" + field.getKey() + "`=?"); values.add(field.getValue().isNull() ? null : field.getValue().asText()); }
                });
                values.add(original.path("id").asText());
                require(jdbc.update("UPDATE " + table + " SET " + String.join(",", setters) + " WHERE id=?", values.toArray()) == 1, "Exact shared restore did not write one row");
                require(rows(jdbc, table, true, original.path("id").asText()).equals(List.of(original)), "Shared restore readback mismatch");
            }
        });
        for (var change : changes) require(rows(jdbc, (String)change.get("table"), false, ((JsonNode)change.get("before")).path("id").asText())
                .equals(List.of(change.get("before"))), "Committed shared restore readback mismatch");
        receipt.put("complete", true); receipt.put("rulesBusinessValuesEqual", true); receipt.put("at", java.time.Instant.now().toString());
        writeNew(directory.resolve("shared-restoration.json"), receipt);
    }

    static void requireChain(JsonNode original, JsonNode observed, List<JsonNode> events) {
        var remaining = new ArrayList<>(events); var ids = new HashSet<String>(); JsonNode cursor = original;
        for (var event : events) require(ids.add(event.path("operationId").asText()), "Duplicate shared operation");
        remaining.sort(java.util.Comparator.comparing(event -> event.path("dbStarted").path("utc").asText()));
        while (!remaining.isEmpty()) {
            JsonNode next = remaining.remove(0);
            require(cursor.equals(next.path("before")), "Shared mutation chain is incomplete"); cursor = next.path("after");
        }
        require(cursor.equals(observed), "Shared row differs without complete operation provenance");
    }
    static void requireCoverageAdvance(JsonNode original, JsonNode observed) {
        require(original.size() == observed.size() && "1".equals(original.path("id").asText()), "Exact coverage record required");
        var fields = original.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!field.equals("observed_through_at")) require(original.path(field).equals(observed.path(field)), "Historical coverage value changed: " + field);
        }
        var first = java.time.LocalDateTime.parse(original.path("observed_through_at").asText().replace(' ', 'T'));
        var last = java.time.LocalDateTime.parse(observed.path("observed_through_at").asText().replace(' ', 'T'));
        require(!last.isBefore(first), "Activity watermark moved backwards");
    }
    private static List<JsonNode> rows(JdbcTemplate jdbc, String table, boolean lock, String id) {
        require(TABLES.contains(table), "Unknown shared table");
        String sql = "SELECT * FROM " + table + (id == null ? "" : " WHERE id=?") + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, ordinal) -> {
            var row = new LinkedHashMap<String, String>();
            for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) row.put(rs.getMetaData().getColumnName(column), rs.getString(column));
            return JSON.valueToTree(row);
        }, id == null ? new Object[0] : new Object[]{id});
    }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static void writeNew(Path path, Object value) throws Exception { Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), java.nio.file.StandardOpenOption.CREATE_NEW); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
