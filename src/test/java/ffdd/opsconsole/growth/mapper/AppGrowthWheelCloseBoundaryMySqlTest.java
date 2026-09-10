package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Executes only the wheel event read/lock predicates against a disposable isolated schema.
 * It never creates an event, spin, ticket, reward, or wallet row outside that UUID schema.
 */
class AppGrowthWheelCloseBoundaryMySqlTest {
    private static final String ENDPOINT = "127.0.0.1:13306";
    private static final String PREFIX = "nexion_wheel_close_it_";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 12, 0);

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(jdbcUrl(ENDPOINT, schema)).contains(":13306/" + schema + "?");
        for (String endpoint : List.of("", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/other")) {
            assertThatThrownBy(() -> jdbcUrl(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalidSchema : List.of("nexion", PREFIX + "a", schema + quote())) {
            assertThatThrownBy(() -> jdbcUrl(ENDPOINT, invalidSchema)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_WHEEL_CLOSE_IT", matches = "true")
    void readAndLockUseTheSameExclusiveUtcEndBoundary() throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches(PREFIX + "[a-f0-9]{32}");
        assertThat(schema.length()).isLessThan(64);
        boolean created = false;
        try (Connection admin = connect("")) {
            assertIsolatedServer(admin);
            try {
                execute(admin, "CREATE DATABASE " + quote() + schema + quote());
                created = true;
                try (Connection fixture = connect(schema)) {
                    assertThat(database(fixture)).isEqualTo(schema);
                    createEventTable(fixture);
                    seed(fixture);
                    freezeUtcTimestamp(fixture);

                    try (SqlSession session = session(fixture)) {
                        AppGrowthWheelMapper mapper = session.getMapper(AppGrowthWheelMapper.class);
                        assertOpen(mapper, "EVT-BEFORE-START", null);
                        assertOpen(mapper, "EVT-AT-START", new WheelEvent(2L, "EVT-AT-START"));
                        assertOpen(mapper, "EVT-BEFORE-END", new WheelEvent(3L, "EVT-BEFORE-END"));
                        assertOpen(mapper, "EVT-AT-END", null);
                        assertOpen(mapper, "EVT-AFTER-END", null);
                        assertOpen(mapper, "EVT-NULL-WINDOW", new WheelEvent(6L, "EVT-NULL-WINDOW"));
                        assertOpen(mapper, "EVT-INACTIVE", null);
                        assertOpen(mapper, "EVT-DELETED", null);
                    }
                }
            } finally {
                if (created) {
                    execute(admin, "DROP DATABASE " + quote() + schema + quote());
                }
            }
        }
    }

    private static void assertOpen(AppGrowthWheelMapper mapper, String eventCode, WheelEvent expected) {
        assertThat(mapper.findOpenWheelEvent(eventCode)).isEqualTo(expected);
        assertThat(mapper.lockOpenWheelEvent(eventCode)).isEqualTo(expected);
    }

    private static SqlSession session(Connection connection) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppGrowthWheelMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
    }

    private static void createEventTable(Connection connection) throws Exception {
        execute(connection, """
                CREATE TABLE nx_event_quest (
                  id BIGINT PRIMARY KEY,
                  quest_code VARCHAR(96) NOT NULL UNIQUE,
                  target_type VARCHAR(32) NOT NULL,
                  status TINYINT NOT NULL,
                  is_deleted TINYINT NOT NULL,
                  starts_at DATETIME NULL,
                  ends_at DATETIME NULL
                ) ENGINE=InnoDB
                """);
    }

    private static void seed(Connection connection) throws Exception {
        insertEvent(connection, 1L, "EVT-BEFORE-START", 1, 0,
                NOW.plusSeconds(1), NOW.plusMinutes(1));
        insertEvent(connection, 2L, "EVT-AT-START", 1, 0,
                NOW, NOW.plusSeconds(1));
        insertEvent(connection, 3L, "EVT-BEFORE-END", 1, 0,
                NOW.minusSeconds(1), NOW.plusSeconds(1));
        insertEvent(connection, 4L, "EVT-AT-END", 1, 0,
                NOW.minusSeconds(1), NOW);
        insertEvent(connection, 5L, "EVT-AFTER-END", 1, 0,
                NOW.minusMinutes(1), NOW.minusSeconds(1));
        insertEvent(connection, 6L, "EVT-NULL-WINDOW", 1, 0, null, null);
        insertEvent(connection, 7L, "EVT-INACTIVE", 0, 0,
                NOW.minusSeconds(1), NOW.plusSeconds(1));
        insertEvent(connection, 8L, "EVT-DELETED", 1, 1,
                NOW.minusSeconds(1), NOW.plusSeconds(1));
    }

    private static void insertEvent(Connection connection, long id, String code, int status, int deleted,
                                    LocalDateTime startsAt, LocalDateTime endsAt) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO nx_event_quest(id,quest_code,target_type,status,is_deleted,starts_at,ends_at)
                VALUES(?,?, 'WHEEL', ?, ?, ?, ?)
                """)) {
            insert.setLong(1, id);
            insert.setString(2, code);
            insert.setInt(3, status);
            insert.setInt(4, deleted);
            if (startsAt == null) insert.setNull(5, java.sql.Types.TIMESTAMP);
            else insert.setObject(5, startsAt);
            if (endsAt == null) insert.setNull(6, java.sql.Types.TIMESTAMP);
            else insert.setObject(6, endsAt);
            insert.executeUpdate();
        }
    }

    private static void freezeUtcTimestamp(Connection connection) throws Exception {
        execute(connection, "SET time_zone = '+00:00'");
        execute(connection, "SET timestamp = UNIX_TIMESTAMP('2026-09-11 12:00:00')");
    }

    private static void assertIsolatedServer(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             var port = statement.executeQuery("SELECT @@port")) {
            assertThat(port.next()).isTrue();
            assertThat(port.getInt(1)).isEqualTo(13306);
        }
    }

    private static String database(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             var selected = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(selected.next()).isTrue();
            return selected.getString(1);
        }
    }

    private static Connection connect(String schema) throws Exception {
        return DriverManager.getConnection(jdbcUrl(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema),
                "root", System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }

    private static String jdbcUrl(String endpoint, String schema) {
        if (!ENDPOINT.equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}"))) {
            throw new IllegalArgumentException("owned UUID schema required");
        }
        return "jdbc:mysql://" + endpoint + "/" + schema + OPTIONS;
    }

    private static String quote() {
        return Character.toString((char) 96);
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
