package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only captures around the existing runtime suites, before Spring can initialize shared rows. */
@EnabledIfEnvironmentVariable(named = "SUPPORT_ANALYTICS_CAPTURE", matches = "before|after")
class SupportAnalyticsCaptureTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void captureActualExclusiveResources() throws Exception {
        var target = SupportRuntimeTarget.current();
        if (!target.analytics()) throw new IllegalStateException("Analytics capture requires its explicit target");
        target.requireEnvironment(System.getenv());
        Path directory = Path.of(required("CS_ENHANCE_EVIDENCE_DIR"));
        Files.createDirectories(directory);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(required("NEXION_DB_URL"),
                required("NEXION_DB_USERNAME"), required("NEXION_DB_PASSWORD")));
        String phase = required("SUPPORT_ANALYTICS_CAPTURE");
        var identity = new LinkedHashMap<String, String>();
        for (var entry : Map.of("taskId", "WORKFLOW_TASK_ID", "stepId", "WORKFLOW_STEP_ID", "checkId", "WORKFLOW_CHECK_ID",
                "runId", "WORKFLOW_RUN_ID", "repo", "WORKFLOW_REPO", "snapshotHash", "WORKFLOW_SNAPSHOT_HASH").entrySet())
            identity.put(entry.getKey(), required(entry.getValue()));
        var ownershipPath = Path.of(required("SUPPORT_RESOURCE_OWNERSHIP"));
        var ownership = JSON.readTree(ownershipPath.toFile());
        var context = new LinkedHashMap<String, Object>();
        context.put("schemaVersion", 2); context.put("purpose", "BUSINESS_PHASE"); context.put("businessAuthorized", true);
        context.put("ownershipMode", "EXCLUSIVE_ANALYTICS"); context.put("resourceOwnership", reference(ownershipPath));
        context.put("identity", identity); context.put("candidate", required("SUPPORT_CANDIDATE"));
        context.put("windowId", identity.get("runId")); context.put("resourceIdentity", ownership.path("resourceIdentity"));
        context.put("businessDeadline", Instant.now().plusSeconds(10800).toString());
        context.put("hardDeadline", Instant.now().plusSeconds(14400).toString());
        SupportExclusiveRuntimeOwnership.requireActual(JSON.valueToTree(context), target, jdbc);
        Exception restorationFailure = null;
        if (phase.equals("after")) try { SupportAnalyticsSharedState.verifyAndRestore(directory, jdbc); }
        catch (Exception failure) { restorationFailure = failure; }

        // Full row values are private evidence; string conversion agrees with the mutation journal's JDBC readback.
        var tables = new LinkedHashMap<String, Object>();
        var rawTables = new LinkedHashMap<String, Object>();
        for (String table : jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME", String.class)) {
            if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Unrecognized table identifier");
            var rows = jdbc.query("SELECT * FROM `" + table + "`", (rs, ordinal) -> {
                var row = new LinkedHashMap<String, String>();
                for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++)
                    row.put(rs.getMetaData().getColumnName(column), rs.getString(column));
                return row;
            });
            rows.sort(java.util.Comparator.comparing(Object::toString));
            tables.put(table, Map.of("rows", rows)); rawTables.put(table, rows);
        }
        var shared = new LinkedHashMap<String, Object>();
        shared.put("schemaVersion", 1); shared.put("at", Instant.now().toString()); shared.put("identity", identity);
        shared.put("databaseIdentity", ownership.path("databaseIdentity")); shared.put("tables", tables);
        Path sharedPath = directory.resolve("shared-" + phase + ".json"); writeNew(sharedPath, shared);

        var storage = MinioClient.builder().endpoint(target.storageEndpoint())
                .credentials(required("NEXION_MINIO_ACCESS_KEY"), required("NEXION_MINIO_SECRET_KEY")).build();
        var objects = new ArrayList<Map<String, Object>>();
        for (var result : storage.listObjects(ListObjectsArgs.builder().bucket(target.bucket()).recursive(true).build())) {
            var object = result.get(); var digest = MessageDigest.getInstance("SHA-256"); long size = 0;
            try (var input = storage.getObject(GetObjectArgs.builder().bucket(target.bucket()).object(object.objectName()).build())) {
                byte[] buffer = new byte[65536]; int read;
                while ((read = input.read(buffer)) != -1) { digest.update(buffer, 0, read); size += read; }
            }
            if (size != object.size()) throw new IllegalStateException("Object changed during capture");
            objects.add(Map.of("bucket", target.bucket(), "key", object.objectName(), "size", size, "sha256", HexFormat.of().formatHex(digest.digest())));
        }
        objects.sort(java.util.Comparator.comparing(item -> item.get("key").toString()));
        var objectEvidence = new LinkedHashMap<String, Object>();
        objectEvidence.put("schemaVersion", 1); objectEvidence.put("event", "R20_OBJECT_" + phase.toUpperCase(java.util.Locale.ROOT));
        objectEvidence.put("complete", true); objectEvidence.put("at", Instant.now().toString());
        for (String key : List.of("candidate", "windowId", "identity", "resourceIdentity")) objectEvidence.put(key, context.get(key));
        objectEvidence.put("databaseIdentity", ownership.path("databaseIdentity")); objectEvidence.put("objects", objects);
        var objectTables = new LinkedHashMap<String, Object>();
        for (String table : List.of("nx_admin", "nx_user", "nx_support_admin_avatar_asset", "nx_support_attachment",
                "nx_support_attachment_command", "nx_support_bulk_job", "nx_admin_account_state")) {
            if (!rawTables.containsKey(table)) throw new IllegalStateException("Required runtime table missing: " + table);
            objectTables.put(table, rawTables.get(table));
        }
        objectEvidence.put("tables", objectTables);
        Path objectPath = directory.resolve("object-" + phase + ".json"); writeNew(objectPath, objectEvidence);
        Exception objectFailure = null;
        if (phase.equals("after") && !JSON.readTree(directory.resolve("object-before.json").toFile()).path("objects").equals(canonicalValue(objects)))
            objectFailure = new IllegalStateException("Private object baseline differs after fixture cleanup");
        if (phase.equals("before")) {
            context.put("rootSharedBefore", reference(sharedPath)); context.put("phaseSharedBefore", reference(sharedPath));
            context.put("objectBefore", reference(objectPath)); writeNew(directory.resolve("actor-context.json"), context);
        }
        SupportExclusiveRuntimeOwnership.requireActual(JSON.valueToTree(context), target, jdbc);
        if (restorationFailure != null) {
            if (objectFailure != null) restorationFailure.addSuppressed(objectFailure);
            throw restorationFailure;
        }
        if (objectFailure != null) throw objectFailure;
    }

    private static void writeNew(Path path, Object data) throws Exception {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data), java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    static com.fasterxml.jackson.databind.JsonNode canonicalValue(Object value) throws Exception {
        return JSON.readTree(JSON.writeValueAsBytes(value));
    }
    private static Map<String, String> reference(Path path) throws Exception {
        return Map.of("path", path.toAbsolutePath().toString(), "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing capture setting: " + key);
        return value;
    }
}
