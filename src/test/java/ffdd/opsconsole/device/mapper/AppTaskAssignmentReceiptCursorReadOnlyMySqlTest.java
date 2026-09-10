package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.api.HistorySnapshotId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual mapper regression in a UUID-owned schema on the separately guarded local MySQL listener. */
@EnabledIfEnvironmentVariable(named = "NEXION_TASK_RECEIPT_CURSOR_IT", matches = "true")
class AppTaskAssignmentReceiptCursorReadOnlyMySqlTest {
    private static final String ENDPOINT = "127.0.0.1:13306";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    @Test
    void highWaterExcludesLaterBackfillsAndContinuationSurvivesAnchorSoftDeletion() throws Exception {
        String endpoint = isolatedEndpoint();
        String schema = "nexion_task_receipt_cursor_it_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("nexion_task_receipt_cursor_it_[a-f0-9]{32}");
        boolean created = false;
        try (Connection admin = DriverManager.getConnection(serverUrl(endpoint), "root", isolatedPassword())) {
            try (Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "`");
                created = true;
            }
            try (Connection fixture = DriverManager.getConnection(schemaUrl(endpoint, schema), "root", isolatedPassword())) {
                assertThat(database(fixture)).isEqualTo(schema);
                createTables(fixture);
                applyMigration(fixture, "scripts/migrations/20260907_task_assignment_recent_read_index.sql");
                applyMigration(fixture, "scripts/migrations/20260907_task_receipt_cursor_index.sql");
                // Both migrations are intended to be startup-rerunnable. A second execution proves the existing
                // matching index path, rather than merely proving an initial ALTER succeeds.
                applyMigration(fixture, "scripts/migrations/20260907_task_assignment_recent_read_index.sql");
                applyMigration(fixture, "scripts/migrations/20260907_task_receipt_cursor_index.sql");
                assertThat(indexColumns(fixture, "nx_compute_task", "idx_compute_task_assignment_recent"))
                        .containsExactly("user_id", "user_device_id", "source_environment", "is_deleted", "status",
                                "created_at", "id");
                assertThat(indexColumns(fixture, "nx_compute_receipt", "idx_receipt_user_time"))
                        .containsExactly("user_id", "completed_at");
                seedVisibleReceipts(fixture);
                try (SqlSession session = session(fixture)) {
                    AppTaskAssignmentMapper mapper = session.getMapper(AppTaskAssignmentMapper.class);
                    assertThat(mapper.maxIssuedReceiptId(7L)).isEqualTo(105L);
                    assertThat(mapper.receiptsAtOrBefore(7L, 105L, 0, 2))
                            .extracting(AppTaskAssignmentMapper.ReceiptRow::receiptNo)
                            .containsExactly("R-105", "R-100");

                    // id 106 arrives after the first page but has an older completed_at; hwm 105 must exclude it.
                    insertVisibleReceipt(fixture, 106L, 7L, "R-106-LATE", LocalDateTime.of(2026, 8, 10, 9, 0));
                    // The next HTTP request has a fresh MyBatis session. Clear this direct-session cache so the
                    // fixture observes the physical insert rather than proving only first-level-cache behavior.
                    session.clearCache();
                    assertThat(mapper.maxIssuedReceiptId(7L)).isEqualTo(106L);
                    assertThat(HistorySnapshotId.resolve("105", () -> mapper.maxIssuedReceiptId(7L))).isEqualTo(105L);
                    assertThat(mapper.receiptsBefore(7L, 105L, LocalDateTime.of(2026, 8, 10, 11, 0), 100L, 10))
                            .extracting(AppTaskAssignmentMapper.ReceiptRow::receiptNo)
                            .containsExactly("R-90");

                    // The cursor contains the original id/time; it does not have to look up R-100 again.
                    try (Statement statement = fixture.createStatement()) {
                        statement.execute("UPDATE nx_compute_receipt SET is_deleted=1 WHERE id=100");
                    }
                    session.clearCache();
                    assertThat(mapper.receiptsBefore(7L, 105L, LocalDateTime.of(2026, 8, 10, 11, 0), 100L, 10))
                            .extracting(AppTaskAssignmentMapper.ReceiptRow::receiptNo)
                            .containsExactly("R-90");
                    assertThatThrownBy(() -> HistorySnapshotId.resolve("107", () -> mapper.maxIssuedReceiptId(7L)))
                            .hasMessage("HISTORY_SNAPSHOT_INVALID");
                }
            }
        } finally {
            if (created) {
                try (Connection admin = DriverManager.getConnection(serverUrl(endpoint), "root", isolatedPassword());
                     Statement statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE `" + schema + "`");
                }
            }
        }
    }

    static String isolatedEndpoint() {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        requireIsolatedEndpoint(endpoint);
        return endpoint;
    }

    static void requireIsolatedEndpoint(String endpoint) {
        if (!ENDPOINT.equals(endpoint)) {
            throw new IllegalStateException("NEXION_ISOLATED_MYSQL_ENDPOINT_MUST_BE_127_0_0_1_13306");
        }
    }

    private static String isolatedPassword() {
        String password = System.getenv("NEXION_ISOLATED_MYSQL_PASSWORD");
        return password == null ? "" : password;
    }

    private static String serverUrl(String endpoint) { return "jdbc:mysql://" + endpoint + "/" + OPTIONS; }
    private static String schemaUrl(String endpoint, String schema) { return "jdbc:mysql://" + endpoint + "/" + schema + OPTIONS; }

    private static String database(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DATABASE()")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static SqlSession session(Connection connection) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppTaskAssignmentMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(connection);
    }

    private static void createTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,status VARCHAR(16),is_deleted TINYINT,sandbox TINYINT)");
            statement.execute("CREATE TABLE nx_user_device (id BIGINT PRIMARY KEY,user_id BIGINT,is_deleted TINYINT,source_environment VARCHAR(16),run_id VARCHAR(96),instance_no VARCHAR(96),name VARCHAR(96),device_type VARCHAR(32),gpu_model VARCHAR(96),vram_total_gb INT)");
            statement.execute("CREATE TABLE nx_compute_task (id BIGINT PRIMARY KEY,task_no VARCHAR(96) UNIQUE,user_id BIGINT,user_device_id BIGINT,is_deleted TINYINT,source_environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,completed_at DATETIME,task_type VARCHAR(64),task_config_id VARCHAR(96),task_name VARCHAR(96),model_name VARCHAR(96),started_at DATETIME,created_at DATETIME)");
            statement.execute("CREATE TABLE nx_compute_receipt (id BIGINT PRIMARY KEY,receipt_no VARCHAR(96),task_no VARCHAR(96),user_id BIGINT,user_device_id BIGINT,source_environment VARCHAR(16),is_deleted TINYINT,earning_status VARCHAR(16),reward_usdt DECIMAL(18,6),reward_nex DECIMAL(18,6),proof_hash VARCHAR(128),client_name VARCHAR(96),completed_at DATETIME,task_type VARCHAR(64))");
        }
    }

    private static void applyMigration(Connection connection, String relativePath) throws Exception {
        String script = Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (left, line) -> left + "\n" + line);
        try (Statement statement = connection.createStatement()) {
            for (String command : script.split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
        }
    }

    private static List<String> indexColumns(Connection connection, String table, String index) throws Exception {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_name FROM information_schema.statistics"
                     + " WHERE table_schema = DATABASE() AND table_name = '" + table + "'"
                     + " AND index_name = '" + index + "' ORDER BY seq_in_index")) {
            var columns = new java.util.ArrayList<String>();
            while (rows.next()) columns.add(rows.getString(1));
            return columns;
        }
    }

    private static void seedVisibleReceipts(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO nx_user VALUES (7,'ACTIVE',0,0),(8,'ACTIVE',0,0)");
            statement.execute("INSERT INTO nx_user_device VALUES (11,7,0,'PRODUCTION','','D-11','Device 11','GPU','A100',80),(12,8,0,'PRODUCTION','','D-12','Other device','GPU','A100',80)");
        }
        insertVisibleReceipt(connection, 105L, 7L, "R-105", LocalDateTime.of(2026, 8, 10, 12, 0));
        insertVisibleReceipt(connection, 100L, 7L, "R-100", LocalDateTime.of(2026, 8, 10, 11, 0));
        insertVisibleReceipt(connection, 90L, 7L, "R-90", LocalDateTime.of(2026, 8, 10, 10, 0));
        insertVisibleReceipt(connection, 999L, 8L, "R-OTHER", LocalDateTime.of(2026, 8, 10, 13, 0));
    }

    private static void insertVisibleReceipt(
            Connection connection, long id, long userId, String receiptNo, LocalDateTime completedAt) throws Exception {
        long deviceId = userId == 7L ? 11L : 12L;
        String taskNo = "CTA-" + id;
        String timestamp = completedAt.toString().replace('T', ' ');
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO nx_compute_task VALUES (" + id + ",'" + taskNo + "'," + userId + "," + deviceId
                    + ",0,'PRODUCTION','COMPLETED','" + timestamp + "','LL','TASK-LL','Task','model','"
                    + completedAt.minusMinutes(1).toString().replace('T', ' ') + "','"
                    + completedAt.minusMinutes(1).toString().replace('T', ' ') + "')");
            statement.execute("INSERT INTO nx_compute_receipt VALUES (" + id + ",'" + receiptNo + "','" + taskNo
                    + "'," + userId + "," + deviceId + ",'PRODUCTION',0,'SETTLED',1.000000,0.000000,'"
                    + "a".repeat(64) + "','Nexion App','" + timestamp + "','LL')");
        }
    }
}
