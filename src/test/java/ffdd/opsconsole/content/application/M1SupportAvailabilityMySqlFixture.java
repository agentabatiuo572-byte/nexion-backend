package ffdd.opsconsole.content.application;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
            return new M1SupportAvailabilityMySqlFixture(admin, schema, true, jdbc);
        } catch (RuntimeException | SQLException ex) {
            if (created) dropOwned(admin, schema);
            admin.close();
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
    }
}