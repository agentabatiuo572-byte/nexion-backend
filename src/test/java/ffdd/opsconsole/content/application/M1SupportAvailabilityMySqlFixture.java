package ffdd.opsconsole.content.application;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Strict opt-in MySQL fixture: a loopback server URL must not select a catalog. */
final class M1SupportAvailabilityMySqlFixture implements AutoCloseable {
    private static final Pattern LOOPBACK_SERVER_URL = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNED_SCHEMA = Pattern.compile("^nx_m1_support_availability_test_[0-9a-f]{32}$");

    private final Connection admin;
    private final String schema;
    private final boolean created;
    private final JdbcTemplate jdbc;
    private final Evidence evidence;

    private record Evidence(Path directory, String runId, String snapshotHash) { }

    private M1SupportAvailabilityMySqlFixture(Connection admin, String schema, boolean created, JdbcTemplate jdbc, Evidence evidence) {
        this.admin = admin;
        this.schema = schema;
        this.created = created;
        this.jdbc = jdbc;
        this.evidence = evidence;
    }

    static M1SupportAvailabilityMySqlFixture openFromEnvironment() throws SQLException {
        return openFromEnvironment(System.getenv());
    }

    // Explicit environment permits pure fault-injection tests without connecting to a server.
    static M1SupportAvailabilityMySqlFixture openFromEnvironment(Map<String, String> environment) throws SQLException {
        String serverUrl = environment.getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        requireServerUrlWithoutCatalog(serverUrl);
        Evidence evidence = evidenceConfiguration(serverUrl, environment);
        String username = environment.getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = environment.get("NEXION_TEST_DB_PASSWORD");
        Connection admin = DriverManager.getConnection(serverUrl, username, password);
        String schema = "nx_m1_support_availability_test_" + UUID.randomUUID().toString().replace("-", "");
        requireOwnedSchema(schema);
        boolean created = false;
        try {
            recordIntent(admin, schema, evidence);
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                created = true;
                recordSchema(admin, schema, "CREATED", evidence);
            }
            DataSource source = fixtureDataSource(fixtureUrl(serverUrl, schema), username, password, schema);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            String currentSchema = jdbc.queryForObject("SELECT DATABASE()", String.class);
            if (!schema.equals(currentSchema)) throw new IllegalStateException("M1 fixture lost owned schema boundary");
            return new M1SupportAvailabilityMySqlFixture(admin, schema, true, jdbc, evidence);
        } catch (RuntimeException | SQLException ex) {
            try {
                if (created) dropOwned(admin, schema, evidence);
            } catch (RuntimeException | SQLException cleanup) {
                ex.addSuppressed(cleanup);
            } finally {
                try { admin.close(); } catch (SQLException cleanup) { ex.addSuppressed(cleanup); }
            }
            throw ex;
        }
    }

    static void requireServerUrlWithoutCatalog(String url) {
        if (url == null || !LOOPBACK_SERVER_URL.matcher(url).matches()) {
            throw new IllegalArgumentException("NEXION_TEST_DB_SERVER_URL must be loopback JDBC MySQL URL with no catalog");
        }
    }

    private static void requireOwnedSchema(String schema) {
        if (schema == null || !OWNED_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("M1 fixture schema is not owned");
        }
    }

    private static String fixtureUrl(String serverUrl, String schema) {
        int query = serverUrl.indexOf('?');
        String suffix = query < 0 ? "" : serverUrl.substring(query);
        return serverUrl.substring(0, query < 0 ? serverUrl.length() : query) + schema + suffix;
    }

    private static DataSource fixtureDataSource(String url, String username, String password, String schema) {
        return new DelegatingDataSource(new DriverManagerDataSource(url, username, password)) {
            @Override
            public Connection getConnection() throws SQLException {
                return assertFixtureConnection(super.getConnection(), schema);
            }

            @Override
            public Connection getConnection(String user, String pass) throws SQLException {
                return assertFixtureConnection(super.getConnection(user, pass), schema);
            }
        };
    }

    private static Connection assertFixtureConnection(Connection connection, String schema) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next() || !schema.equals(result.getString(1))) {
                connection.close();
                throw new SQLException("M1 fixture connection is outside its owned schema");
            }
            return connection;
        } catch (SQLException ex) {
            try { connection.close(); } catch (SQLException ignored) { }
            throw ex;
        }
    }
    JdbcTemplate jdbc() { return jdbc; }

    @Override
    public void close() throws SQLException {
        try {
            if (created) dropOwned(admin, schema, evidence);
        } finally {
            admin.close();
        }
    }

    private static void dropOwned(Connection admin, String schema, Evidence evidence) throws SQLException {
        requireOwnedSchema(schema);
        try (var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + schema + "`");
        }
        recordSchema(admin, schema, "DROPPED", evidence);
    }

    private static void recordIntent(Connection admin, String schema, Evidence evidence) throws SQLException {
        if (evidence == null) return;
        int port = verifyReadback(admin, schema, false);
        // PREPARED locates an uncertain CREATE outcome; it never establishes ownership.
        appendEvidence(evidence, "schema-intents.jsonl", Map.of(
                "at", Instant.now().toString(), "schema", schema, "action", "PREPARED",
                "runId", evidence.runId(), "snapshotHash", evidence.snapshotHash(), "databasePort", port,
                "schemaExistsAtReadback", false, "successfulCreate", false, "ownsSchema", false));
    }

    private static void recordSchema(Connection admin, String schema, String action, Evidence evidence) throws SQLException {
        if (evidence == null) return;
        boolean exists = "CREATED".equals(action);
        int port = verifyReadback(admin, schema, exists);
        appendEvidence(evidence, "owned-schemas.jsonl", Map.of(
                "at", Instant.now().toString(), "schema", schema, "action", action,
                "runId", evidence.runId(), "snapshotHash", evidence.snapshotHash(), "databasePort", port,
                "schemaExistsAtReadback", exists));
    }

    private static int verifyReadback(Connection admin, String schema, boolean exists) throws SQLException {
        requireOwnedSchema(schema);
        String serverUrl = admin.getMetaData().getURL();
        requireServerUrlWithoutCatalog(serverUrl);
        if (java.net.URI.create(serverUrl.substring("jdbc:".length())).getPort() != 33329) {
            throw new SQLException("Ticket schema evidence requires the authorized isolated MySQL port");
        }
        int port;
        try (var statement = admin.createStatement(); var rows = statement.executeQuery("SELECT @@port")) {
            if (!rows.next() || (port = rows.getInt(1)) != 33329 || rows.next()) {
                throw new SQLException("Actual ticket schema server port is not authorized");
            }
        }
        try (var statement = admin.prepareStatement("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != (exists ? 1 : 0) || rows.next()) {
                    throw new SQLException("Owned ticket schema readback failed");
                }
            }
        }
        return port;
    }

    private static synchronized void appendEvidence(Evidence evidence, String filename, Map<String, Object> record) throws SQLException {
        try {
            Path output = evidence.directory();
            Files.createDirectories(output);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(record) + "\n";
            try (var channel = FileChannel.open(output.resolve(filename), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        } catch (java.io.IOException ex) {
            throw new SQLException("Owned ticket schema evidence could not be saved", ex);
        }
    }

    private static Evidence evidenceConfiguration(String serverUrl, Map<String, String> environment) throws SQLException {
        String directory = environment.get("SUPPORT_TICKET_SCHEMA_EVIDENCE_DIR");
        if (directory == null || directory.isBlank()) return null;
        if (java.net.URI.create(serverUrl.substring("jdbc:".length())).getPort() != 33329) {
            throw new SQLException("Ticket schema evidence requires the authorized isolated MySQL port");
        }
        String run = environment.get("WORKFLOW_RUN_ID");
        String snapshot = environment.get("WORKFLOW_SNAPSHOT_HASH");
        if (run == null || !run.matches("[a-zA-Z0-9_-]+") || snapshot == null || !snapshot.matches("[a-f0-9]{64}")) {
            throw new SQLException("Current native identity is required for ticket schema evidence");
        }
        return new Evidence(Path.of(directory), run, snapshot);
    }
}
