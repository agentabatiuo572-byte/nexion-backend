package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure JDBC-result mocks: never starts Spring or opens a connection. */
class SupportAnalyticsSnapshotTest {
    private final ObjectMapper json = new ObjectMapper();
    private SupportAnalyticsSnapshot.Capture capture(String table, List<String> columns, String[]... rows) throws Exception {
        var metadata = (ResultSetMetaData) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> columns.size(); case "getColumnName" -> columns.get((int) args[0] - 1);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        var index = new AtomicInteger(-1);
        var rs = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> metadata; case "next" -> index.incrementAndGet() < rows.length;
            case "getString" -> rows[index.get()][(int) args[0] - 1];
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return SupportAnalyticsSnapshot.capture(rs, table);
    }
    private JsonNode snapshot(String table, SupportAnalyticsSnapshot.Capture capture) {
        return json.valueToTree(Map.of("schemaVersion", 2, "tables", Map.of(table, capture.summary())));
    }
    @Test void emptyTablesKeepColumnsAndNullDiffersFromEmptyAndLiteralNull() throws Exception {
        var empty = capture("nx_log", List.of("id", "payload"));
        assertThat(empty.summary()).containsEntry("rowCount", 0L).containsEntry("columns", List.of("id", "payload"));
        assertThat(empty.summary()).doesNotContainKey("rows");
        SupportAnalyticsSnapshot.validate(snapshot("nx_log", empty));
        var nullValue = capture("nx_log", List.of("id", "payload"), new String[]{"1", null});
        for (String value : List.of("", "null")) assertThat(capture("nx_log", List.of("id", "payload"), new String[]{"1", value}).summary().get("sha256")).isNotEqualTo(nullValue.summary().get("sha256"));
    }
    @Test void digestIgnoresRowAndFieldOrderButCountsDuplicatesAndDetectsSameCountChangedValues() throws Exception {
        var first = capture("nx_wallet", List.of("id", "balance"), new String[]{"1", "10"}, new String[]{"2", "20"});
        var reordered = capture("nx_wallet", List.of("balance", "id"), new String[]{"20", "2"}, new String[]{"10", "1"});
        assertThat(reordered.summary().get("sha256")).isEqualTo(first.summary().get("sha256"));
        var changed = capture("nx_wallet", List.of("id", "balance"), new String[]{"1", "11"}, new String[]{"2", "20"});
        assertThat(changed.summary().get("rowCount")).isEqualTo(first.summary().get("rowCount"));
        assertThat(changed.summary().get("sha256")).isNotEqualTo(first.summary().get("sha256"));
        var duplicate = capture("nx_wallet", List.of("id", "balance"), new String[]{"1", "10"}, new String[]{"1", "10"});
        assertThat(duplicate.summary().get("rowCount")).isEqualTo(2L);
        assertThat(duplicate.summary().get("sha256")).isNotEqualTo(first.summary().get("sha256"));
    }
    @Test void unrelatedLargePayloadDoesNotGrowSnapshotAndObjectEvidenceOnlyKeepsConsumedColumns() throws Exception {
        String large = "private-payload".repeat(75000);
        var small = capture("nx_log", List.of("id", "payload"), new String[]{"1", "small"});
        var bigger = capture("nx_log", List.of("id", "payload"), new String[]{"1", large});
        assertThat(json.writeValueAsBytes(bigger.summary()).length).isEqualTo(json.writeValueAsBytes(small.summary()).length).isLessThan(512);
        var object = capture("nx_admin", List.of("id", "username", "password_hash", "payload"), new String[]{"1", "owner", "secret", large});
        assertThat(object.objectRows()).containsExactly(Map.of("id", "1", "username", "owner"));
        assertThat(json.writeValueAsBytes(object).length).isLessThan(1024);
        var command = capture("nx_support_attachment_command", List.of("actor_type", "actor_id", "operation", "command_key", "attachment_id", "created_at"), new String[]{"ADMIN", "3", "UPLOAD", "key", "attachment", "2026-10-12 00:00:00"});
        assertThat(command.objectRows().get(0)).hasSize(4).containsAllEntriesOf(Map.of("operation", "UPLOAD", "actor_type", "ADMIN", "actor_id", "3", "command_key", "key"));
    }
    @Test void independentObjectSchemasKeepAllConsumerKeysAndRejectMissingRequiredColumns() throws Exception {
        // Independently pinned to the seven current DDL/metadata contracts, including the command's composite PK.
        var schemas = Map.of(
            "nx_admin", List.of("id", "username", "password_hash"), "nx_user", List.of("id", "nickname"),
            "nx_support_admin_avatar_asset", List.of("id", "object_key"), "nx_support_attachment", List.of("id", "object_key"),
            "nx_support_attachment_command", List.of("actor_type", "actor_id", "operation", "command_key", "attachment_id", "created_at"),
            "nx_support_bulk_job", List.of("id", "asset_json"), "nx_admin_account_state", List.of("id", "admin_id"));
        var consumed = Map.of("nx_admin", List.of("id", "username"), "nx_user", List.of("id"),
            "nx_support_admin_avatar_asset", List.of("id"), "nx_support_attachment", List.of("id"),
            "nx_support_attachment_command", List.of("actor_type", "actor_id", "operation", "command_key"),
            "nx_support_bulk_job", List.of("id"), "nx_admin_account_state", List.of("id"));
        assertThat(SupportAnalyticsSnapshot.OBJECT_COLUMNS.keySet()).containsExactlyInAnyOrderElementsOf(schemas.keySet());
        for (var schema : schemas.entrySet()) {
            String table = schema.getKey(); List<String> columns = schema.getValue();
            assertThat(capture(table, columns).objectRows()).isEmpty();
            String[] values = columns.stream().map(column -> "original-" + column).toArray(String[]::new);
            var captured = capture(table, columns, values);
            assertThat(captured.objectRows()).hasSize(1);
            assertThat(captured.objectRows().get(0).keySet()).containsExactlyInAnyOrderElementsOf(consumed.get(table));
            for (String column : consumed.get(table)) {
                assertThat(captured.objectRows().get(0).get(column)).isEqualTo("original-" + column);
                var missing = new java.util.ArrayList<>(columns); missing.remove(column);
                assertThatThrownBy(() -> capture(table, missing)).isInstanceOf(IllegalStateException.class);
            }
        }
        var columns = schemas.get("nx_support_attachment_command");
        var first = capture("nx_support_attachment_command", columns, new String[]{"ADMIN", "3", "UPLOAD", "key", "first", "2026-10-12 00:00:00"});
        var changed = capture("nx_support_attachment_command", columns, new String[]{"ADMIN", "3", "UPLOAD", "key", "second", "2026-10-12 00:00:00"});
        assertThat(changed.objectRows()).isEqualTo(first.objectRows());
        assertThat(changed.summary().get("rowCount")).isEqualTo(first.summary().get("rowCount"));
        assertThat(changed.summary().get("sha256")).isNotEqualTo(first.summary().get("sha256"));
    }
    @Test void restorationKeepsEveryOriginalValueAndBothFormatsReadBackWhileUnknownOrCorruptFormatsFail() throws Exception {
        for (String table : SupportAnalyticsSnapshot.SHARED_ROWS) {
            var captured = capture(table, List.of("id", "permission", "nullable"), new String[]{"1", "original", null});
            var document = snapshot(table, captured); JsonNode rows = SupportAnalyticsSnapshot.requiredRows(document, table);
            assertThat(rows.get(0).path("permission").asText()).isEqualTo("original"); assertThat(rows.get(0).path("nullable").isNull()).isTrue();
            var legacy = json.valueToTree(Map.of("schemaVersion", 1, "tables", Map.of(table, Map.of("rows", rows))));
            assertThat(SupportAnalyticsSnapshot.requiredRows(legacy, table)).isEqualTo(rows);
            var missing = document.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) missing.path("tables").path(table)).remove("rows");
            assertThatThrownBy(() -> SupportAnalyticsSnapshot.validate(missing)).isInstanceOf(IllegalStateException.class);
            var corrupt = document.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) corrupt.path("tables").path(table)).put("rowCount", 0);
            assertThatThrownBy(() -> SupportAnalyticsSnapshot.validate(corrupt)).isInstanceOf(IllegalStateException.class);
        }
        for (String version : List.of("0", "3", "4294967298", "\"2\"", "null")) assertThatThrownBy(() -> SupportAnalyticsSnapshot.validate(json.readTree("{\"schemaVersion\":" + version + ",\"tables\":{}}"))).isInstanceOf(IllegalStateException.class);
        var overflowing = snapshot("nx_log", capture("nx_log", List.of("id"))).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) overflowing.path("tables").path("nx_log")).put("rowCount", new java.math.BigInteger("18446744073709551616"));
        assertThatThrownBy(() -> SupportAnalyticsSnapshot.validate(overflowing)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SupportAnalyticsSnapshot.requiredRows(snapshot("nx_log", capture("nx_log", List.of("id"))), "nx_admin_role_permission")).isInstanceOf(IllegalStateException.class);
    }
    @Test void historicalHashesAllowNewRowsButRejectChangesOrMissingDuplicateAndLegacyDoesToo() throws Exception {
        var captured = capture("nx_admin_permission", List.of("id", "code"), new String[]{"1", "read"}, new String[]{"1", "read"});
        var document = snapshot("nx_admin_permission", captured);
        var original = json.valueToTree(Map.of("id", "1", "code", "read")); var added = json.valueToTree(Map.of("id", "2", "code", "new"));
        SupportAnalyticsSnapshot.requireHistoricalRows(document, "nx_admin_permission", List.of(original, added, original));
        assertThatThrownBy(() -> SupportAnalyticsSnapshot.requireHistoricalRows(document, "nx_admin_permission", List.of(original, added))).isInstanceOf(IllegalStateException.class);
        var changed = json.valueToTree(Map.of("id", "1", "code", "write"));
        assertThatThrownBy(() -> SupportAnalyticsSnapshot.requireHistoricalRows(document, "nx_admin_permission", List.of(original, changed))).isInstanceOf(IllegalStateException.class);
        var legacy = json.valueToTree(Map.of("schemaVersion", 1, "tables", Map.of("nx_admin_permission", Map.of("rows", List.of(original, original)))));
        assertThatThrownBy(() -> SupportAnalyticsSnapshot.requireHistoricalRows(legacy, "nx_admin_permission", List.of(original))).isInstanceOf(IllegalStateException.class);
        SupportAnalyticsSnapshot.requireHistoricalRows(legacy, "nx_admin_permission", List.of(original, original));
    }
    @Test void fileHashStreamsExactBytes(@TempDir Path directory) throws Exception {
        byte[] bytes = new byte[1024 * 1024]; Arrays.fill(bytes, (byte) 17); Path file = directory.resolve("synthetic.bin"); Files.write(file, bytes);
        assertThat(SupportAnalyticsSnapshot.fileHash(file)).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
