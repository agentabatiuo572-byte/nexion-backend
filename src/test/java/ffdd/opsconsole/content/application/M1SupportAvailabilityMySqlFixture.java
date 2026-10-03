package ffdd.opsconsole.content.application;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    private M1SupportAvailabilityMySqlFixture(Connection admin, String schema, boolean created, JdbcTemplate jdbc) {
        this.admin = admin;
        this.schema = schema;
        this.created = created;
        this.jdbc = jdbc;
    }

    static M1SupportAvailabilityMySqlFixture openFromEnvironment() throws SQLException {
        String serverUrl = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        requireServerUrlWithoutCatalog(serverUrl);
        requireEvidenceConfiguration(serverUrl);
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        Connection admin = DriverManager.getConnection(serverUrl, username, password);
        String schema = "nx_m1_support_availability_test_" + UUID.randomUUID().toString().replace("-", "");
        requireOwnedSchema(schema);
        boolean created = false;
        try {
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                created = true;
            }
            DataSource source = fixtureDataSource(fixtureUrl(serverUrl, schema), username, password, schema);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            String currentSchema = jdbc.queryForObject("SELECT DATABASE()", String.class);
            if (!schema.equals(currentSchema)) throw new IllegalStateException("M1 fixture lost owned schema boundary");
            recordSchema(admin, schema, "CREATED");
            return new M1SupportAvailabilityMySqlFixture(admin, schema, true, jdbc);
        } catch (RuntimeException | SQLException ex) {
            try {
                if (created) dropOwned(admin, schema);
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
            if (created) dropOwned(admin, schema);
        } finally {
            admin.close();
        }
    }

    private static void dropOwned(Connection admin, String schema) throws SQLException {
        requireOwnedSchema(schema);
        try (var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + schema + "`");
        }
        recordSchema(admin, schema, "DROPPED");
    }

    private static void recordSchema(Connection admin, String schema, String action) throws SQLException {
        String directory = System.getenv("SUPPORT_TICKET_SCHEMA_EVIDENCE_DIR");
        if (directory == null || directory.isBlank()) return;
        requireOwnedSchema(schema);
        String serverUrl = admin.getMetaData().getURL();
        requireServerUrlWithoutCatalog(serverUrl);
        requireEvidenceConfiguration(serverUrl);
        int port = java.net.URI.create(serverUrl.substring("jdbc:".length())).getPort();
        String run = System.getenv("WORKFLOW_RUN_ID");
        String snapshot = System.getenv("WORKFLOW_SNAPSHOT_HASH");
        try (var statement = admin.prepareStatement("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != ("CREATED".equals(action) ? 1 : 0)) {
                    throw new SQLException("Owned ticket schema readback failed");
                }
            }
        }
        try {
            Path output = Path.of(directory);
            Files.createDirectories(output);
            String record = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "at", Instant.now().toString(), "schema", schema, "action", action,
                    "runId", run, "snapshotHash", snapshot, "databasePort", port,
                    "schemaExistsAtReadback", "CREATED".equals(action)));
            Files.writeString(output.resolve("owned-schemas.jsonl"), record + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            throw new SQLException("Owned ticket schema evidence could not be saved", ex);
        }
    }

    private static void requireEvidenceConfiguration(String serverUrl) throws SQLException {
        String directory = System.getenv("SUPPORT_TICKET_SCHEMA_EVIDENCE_DIR");
        if (directory == null || directory.isBlank()) return;
        if (java.net.URI.create(serverUrl.substring("jdbc:".length())).getPort() != 33329) {
            throw new SQLException("Ticket schema evidence requires the authorized isolated MySQL port");
        }
        String run = System.getenv("WORKFLOW_RUN_ID");
        String snapshot = System.getenv("WORKFLOW_SNAPSHOT_HASH");
        if (run == null || !run.matches("[a-zA-Z0-9_-]+") || snapshot == null || !snapshot.matches("[a-f0-9]{64}")) {
            throw new SQLException("Current native identity is required for ticket schema evidence");
        }
    }
}
