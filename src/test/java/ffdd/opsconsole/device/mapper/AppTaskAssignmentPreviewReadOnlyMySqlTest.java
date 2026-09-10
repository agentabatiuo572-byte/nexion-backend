package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Executes the App preview's generated MyBatis multi-device UNION against an explicitly isolated
 * database. It has no business-database fallback and creates only a UUID-owned disposable schema.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TASK_ASSIGNMENT_PREVIEW_IT", matches = "true")
class AppTaskAssignmentPreviewReadOnlyMySqlTest {
    private static final String ENDPOINT = "127.0.0.1:13306";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    @Test
    void previewUsesOnlyOwnedProductionDevicesAndKeepsAllActivePlusTenNewestCompletedPerDevice()
            throws Exception {
        String endpoint = isolatedEndpoint();
        String schema = "nexion_task_preview_it_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("nexion_task_preview_it_[a-f0-9]{32}");
        boolean created = false;
        try (Connection admin = DriverManager.getConnection(serverUrl(endpoint), "root", isolatedPassword())) {
            try (Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + (char) 96 + schema + (char) 96);
                created = true;
            }
            try (Connection fixture = DriverManager.getConnection(schemaUrl(endpoint, schema), "root", isolatedPassword())) {
                assertThat(database(fixture)).isEqualTo(schema);
                createTables(fixture);
                applyMigration(fixture, "scripts/migrations/20260907_task_assignment_recent_read_index.sql");
                applyMigration(fixture, "scripts/migrations/20260907_task_assignment_recent_read_index.sql");
                seed(fixture);

                try (SqlSession session = session(fixture)) {
                    AppTaskAssignmentMapper mapper = session.getMapper(AppTaskAssignmentMapper.class);
                    List<Long> ownedIds = mapper.ownedDevices(7L).stream()
                            .map(AppTaskAssignmentMapper.DeviceRow::id)
                            .toList();
                    assertThat(ownedIds).containsExactly(11L, 12L);

                    List<AppTaskAssignmentMapper.AssignmentRow> rows =
                            mapper.assignmentsForDevices(7L, "PRODUCTION", ownedIds);
                    assertThat(mapper.assignmentsForDevices(9L, "PRODUCTION", List.of(22L))).isEmpty();

                    assertThat(rows.stream().filter(row -> row.deviceId().equals(11L)
                                    && active(row.status())).map(AppTaskAssignmentMapper.AssignmentRow::taskNo))
                            .containsExactly("CTA-11-CLAIMED", "CTA-11-RUNNING");
                    assertThat(rows.stream().filter(row -> row.deviceId().equals(12L)
                                    && active(row.status())).map(AppTaskAssignmentMapper.AssignmentRow::taskNo))
                            .containsExactly("CTA-12-RUNNING");
                    assertThat(completedTaskNos(rows, 11L)).containsExactly(
                            "CTA-11-C12", "CTA-11-C11", "CTA-11-C10", "CTA-11-C09", "CTA-11-C08",
                            "CTA-11-C07", "CTA-11-C06", "CTA-11-C05", "CTA-11-C04", "CTA-11-C03");
                    assertThat(completedTaskNos(rows, 12L)).containsExactly(
                            "CTA-12-C11", "CTA-12-C10", "CTA-12-C09", "CTA-12-C08", "CTA-12-C07",
                            "CTA-12-C06", "CTA-12-C05", "CTA-12-C04", "CTA-12-C03", "CTA-12-C02");
                    assertThat(rows).filteredOn(row -> row.taskNo().equals("CTA-11-C12"))
                            .extracting(AppTaskAssignmentMapper.AssignmentRow::receiptNo)
                            .containsExactly("RCT-11-C12");
                    assertThat(rows).extracting(AppTaskAssignmentMapper.AssignmentRow::taskNo)
                            .doesNotContain("CTA-11-C02", "CTA-11-C01", "CTA-12-C01",
                                    "CTA-11-UNOWNED", "CTA-11-SANDBOX", "CTA-11-DELETED", "CTA-21-OTHER",
                                    "CTA-22-INACTIVE-USER");
                }
            }
        } finally {
            if (created) {
                try (Connection admin = DriverManager.getConnection(serverUrl(endpoint), "root", isolatedPassword());
                     Statement statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE " + (char) 96 + schema + (char) 96);
                }
            }
        }
    }

    static String isolatedEndpoint() {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        if (!ENDPOINT.equals(endpoint)) {
            throw new IllegalStateException("NEXION_ISOLATED_MYSQL_ENDPOINT_MUST_BE_127_0_0_1_13306");
        }
        return endpoint;
    }

    private static String isolatedPassword() {
        String password = System.getenv("NEXION_ISOLATED_MYSQL_PASSWORD");
        return password == null ? "" : password;
    }

    private static String serverUrl(String endpoint) {
        return "jdbc:mysql://" + endpoint + "/" + OPTIONS;
    }

    private static String schemaUrl(String endpoint, String schema) {
        return "jdbc:mysql://" + endpoint + "/" + schema + OPTIONS;
    }

    private static String database(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATABASE()")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static SqlSession session(Connection connection) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppTaskAssignmentMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
    }

    private static void createTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE nx_user (
                      id BIGINT PRIMARY KEY,
                      status VARCHAR(16) NOT NULL,
                      is_deleted TINYINT NOT NULL,
                      sandbox TINYINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE nx_user_device (
                      id BIGINT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      instance_no VARCHAR(96) NOT NULL,
                      device_type VARCHAR(32) NOT NULL,
                      product_tier VARCHAR(32),
                      name VARCHAR(96),
                      status VARCHAR(16),
                      product_code VARCHAR(64),
                      purchased_at DATETIME,
                      activated_at DATETIME,
                      vram_total_gb INT,
                      dc_location VARCHAR(32),
                      is_deleted TINYINT NOT NULL,
                      source_environment VARCHAR(16) NOT NULL,
                      run_id VARCHAR(96) NOT NULL,
                      ownership_status VARCHAR(16) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE nx_user_device_runtime (
                      user_device_id BIGINT NOT NULL,
                      online_status VARCHAR(16),
                      paused_reason VARCHAR(128),
                      is_deleted TINYINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE nx_compute_dc_ops_state (
                      dc_location VARCHAR(32) NOT NULL,
                      dispatch_paused TINYINT,
                      is_deleted TINYINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE nx_compute_task (
                      id BIGINT PRIMARY KEY,
                      task_no VARCHAR(96) NOT NULL UNIQUE,
                      user_id BIGINT NOT NULL,
                      user_device_id BIGINT NOT NULL,
                      task_config_id VARCHAR(96),
                      task_name VARCHAR(96),
                      task_type VARCHAR(64),
                      model_name VARCHAR(96),
                      client_name VARCHAR(96),
                      status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                      reward_usdt DECIMAL(18,6),
                      required_seconds INT,
                      task_lock_minutes INT,
                      started_at DATETIME,
                      lease_expires_at DATETIME,
                      completed_at DATETIME,
                      source_environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                      completion_nonce VARCHAR(128),
                      proof_expires_at DATETIME,
                      created_at DATETIME NOT NULL,
                      is_deleted TINYINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE nx_compute_receipt (
                      task_no VARCHAR(96) NOT NULL,
                      source_environment VARCHAR(16) NOT NULL,
                      is_deleted TINYINT NOT NULL,
                      receipt_no VARCHAR(96)
                    )
                    """);
        }
    }

    private static void applyMigration(Connection connection, String relativePath) throws Exception {
        String script = Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (left, line) -> left + "\n" + line);
        try (Statement statement = connection.createStatement()) {
            for (String command : script.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
    }

    private static void seed(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO nx_user VALUES (7,'ACTIVE',0,0),(8,'ACTIVE',0,0),(9,'DISABLED',0,0)");
            statement.execute("""
                    INSERT INTO nx_user_device VALUES
                    (11,7,'D-11','GPU','S1','Owned 11','ACTIVE','gpu', '2026-08-01 00:00:00',
                     '2026-08-01 00:00:00',24,'SG',0,'PRODUCTION','','OWNED'),
                    (12,7,'D-12','GPU','S1','Owned 12','ACTIVE','gpu', '2026-08-01 00:00:00',
                     '2026-08-01 00:00:00',24,'SG',0,'PRODUCTION','','OWNED'),
                    (13,7,'D-13','GPU','S1','Not owned','ACTIVE','gpu', '2026-08-01 00:00:00',
                     '2026-08-01 00:00:00',24,'SG',0,'PRODUCTION','','LEASED'),
                    (21,8,'D-21','GPU','S1','Other user','ACTIVE','gpu', '2026-08-01 00:00:00',
                     '2026-08-01 00:00:00',24,'SG',0,'PRODUCTION','','OWNED'),
                    (22,9,'D-22','GPU','S1','Inactive user','ACTIVE','gpu', '2026-08-01 00:00:00',
                     '2026-08-01 00:00:00',24,'SG',0,'PRODUCTION','','OWNED')
                    """);
        }
        LocalDateTime base = LocalDateTime.of(2026, 9, 11, 10, 0);
        insertTask(connection, 11_901L, "CTA-11-CLAIMED", 7L, 11L, "CLAIMED", "PRODUCTION", 0,
                base.plusHours(3), null);
        insertTask(connection, 11_902L, "CTA-11-RUNNING", 7L, 11L, "RUNNING", "PRODUCTION", 0,
                base.plusHours(2), null);
        insertTask(connection, 12_901L, "CTA-12-RUNNING", 7L, 12L, "RUNNING", "PRODUCTION", 0,
                base.plusHours(1), null);
        for (int index = 1; index <= 12; index++) {
            LocalDateTime created = base.plusMinutes(index == 11 ? 12 : index);
            insertTask(connection, 11_000L + index, completedNo(11L, index), 7L, 11L,
                    index == 12 ? "completed" : "COMPLETED", "PRODUCTION", 0, created, created.plusMinutes(1));
        }
        for (int index = 1; index <= 11; index++) {
            LocalDateTime created = base.plusMinutes(index);
            insertTask(connection, 12_000L + index, completedNo(12L, index), 7L, 12L,
                    "COMPLETED", "PRODUCTION", 0, created, created.plusMinutes(1));
        }
        insertTask(connection, 13_001L, "CTA-11-UNOWNED", 7L, 13L, "COMPLETED", "PRODUCTION", 0,
                base.plusHours(4), base.plusHours(4));
        insertTask(connection, 13_002L, "CTA-11-SANDBOX", 7L, 11L, "COMPLETED", "SANDBOX", 0,
                base.plusHours(4), base.plusHours(4));
        insertTask(connection, 13_003L, "CTA-11-DELETED", 7L, 11L, "COMPLETED", "PRODUCTION", 1,
                base.plusHours(4), base.plusHours(4));
        insertTask(connection, 21_001L, "CTA-21-OTHER", 8L, 21L, "COMPLETED", "PRODUCTION", 0,
                base.plusHours(4), base.plusHours(4));
        insertTask(connection, 22_001L, "CTA-22-INACTIVE-USER", 9L, 22L, "COMPLETED", "PRODUCTION", 0,
                base.plusHours(4), base.plusHours(4));
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO nx_compute_receipt(task_no, source_environment, is_deleted, receipt_no)
                    VALUES ('CTA-11-C12','PRODUCTION',0,'RCT-11-C12')
                    """);
        }
    }

    private static void insertTask(Connection connection, long id, String taskNo, long userId, long deviceId,
                                   String status, String sourceEnvironment, int isDeleted,
                                   LocalDateTime createdAt, LocalDateTime completedAt) throws Exception {
        String created = timestamp(createdAt);
        String completed = completedAt == null ? "NULL" : "'" + timestamp(completedAt) + "'";
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO nx_compute_task(
                      id,task_no,user_id,user_device_id,task_config_id,task_name,task_type,model_name,client_name,
                      status,reward_usdt,required_seconds,task_lock_minutes,started_at,lease_expires_at,completed_at,
                      source_environment,completion_nonce,proof_expires_at,created_at,is_deleted
                    ) VALUES (
                    """ + id + ",'" + taskNo + "'," + userId + "," + deviceId
                    + ",'TASK-IG','Task','IG','model','Nexion App','" + status + "',"
                    + BigDecimal.ONE + ",30,30,'" + created + "','" + timestamp(createdAt.plusMinutes(30)) + "',"
                    + completed + ",'" + sourceEnvironment + "','nonce-" + id + "',NULL,'"
                    + created + "'," + isDeleted + ")");
        }
    }

    private static String completedNo(long deviceId, int index) {
        return "CTA-" + deviceId + "-C" + String.format("%02d", index);
    }

    private static List<String> completedTaskNos(List<AppTaskAssignmentMapper.AssignmentRow> rows, long deviceId) {
        return rows.stream().filter(row -> row.deviceId().equals(deviceId) && "COMPLETED".equalsIgnoreCase(row.status()))
                .map(AppTaskAssignmentMapper.AssignmentRow::taskNo).toList();
    }

    private static boolean active(String status) {
        return "CLAIMED".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status);
    }

    private static String timestamp(LocalDateTime value) {
        return value.toString().replace('T', ' ');
    }
}
