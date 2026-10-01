package ffdd.opsconsole.home.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** #394: run the production Home SQL in a guarded, disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_HOME_GRID_MYSQL_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppHomeOnGridHeartbeatMySqlTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private AppHomeOverviewMapper mapper;
    private String database;

    @BeforeAll
    void openOwnedSchema() throws Exception {
        assertThat(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")).isEqualTo("127.0.0.1:13306");
        String expectedUuid = System.getenv("NEXION_ISOLATED_MYSQL_UUID");
        String expectedDataDir = System.getenv("NEXION_ISOLATED_MYSQL_DATADIR");
        assertThat(expectedUuid).isNotBlank();
        assertThat(expectedDataDir).isNotBlank();
        connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:13306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "root", "");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid", String.class)).isEqualTo(expectedUuid);
        assertThat(normalizePath(jdbc.queryForObject("SELECT @@datadir", String.class)))
                .isEqualTo(normalizePath(expectedDataDir));
        database = "nx_home_grid_394_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_home_grid_394_[a-f0-9]{32}");
        jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        jdbc.execute("USE `" + database + "`");
        jdbc.execute("SET time_zone = '+00:00'");
        jdbc.execute("SET timestamp = UNIX_TIMESTAMP('2026-09-30 12:00:00')");
        createTables();
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("home-grid-394", new SpringManagedTransactionFactory(),
                new SingleConnectionDataSource(connection, true)));
        configuration.addMapper(AppHomeOverviewMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(AppHomeOverviewMapper.class);
        System.out.println("#394 isolated schema=" + database + "; identity verified; frozen SQL clock=2026-09-30T12:00:00Z");
    }

    @AfterAll
    void closeOwnedSchema() throws Exception {
        try {
            if (database != null && jdbc != null) {
                assertThat(database).matches("nx_home_grid_394_[a-f0-9]{32}");
                jdbc.execute("DROP DATABASE IF EXISTS `" + database + "`");
                System.out.println("#394 dropped own schema=" + database);
            }
        } finally {
            if (connection != null) connection.close();
        }
    }

    @BeforeEach
    void seedOneOwnedDeviceAndTask() {
        for (String table : List.of("nx_compute_task", "nx_user_device_runtime", "nx_user_device",
                "nx_compute_datacenter", "nx_user")) jdbc.execute("DELETE FROM " + table);
        jdbc.execute("INSERT INTO nx_user VALUES (7,'ACTIVE',0,0)");
        jdbc.execute("INSERT INTO nx_compute_datacenter VALUES ('DC-1','Tokyo DC','Tokyo',0)");
        jdbc.execute("INSERT INTO nx_user_device VALUES "
                + "(7,7,'DEVICE','OWNED','ACTIVE','2026-09-29 12:00:00',NULL,0,0,'DC-1','GPU-1')");
        jdbc.execute("INSERT INTO nx_user_device_runtime VALUES "
                + "(7,'ONLINE','2026-09-30 12:00:00',100,1,0)");
        jdbc.execute("INSERT INTO nx_compute_task "
                + "(id,user_device_id,user_id,source_environment,status,reward_usdt,required_seconds,paused_at,is_deleted,"
                + "task_no,client_name,created_at,updated_at,completed_at) VALUES "
                + "(70,7,7,'PRODUCTION','RUNNING',12.5,100,NULL,0,'CTA-394','NexGrid App',NOW(6),NOW(6),NULL)");
    }

    record Case(String name, String mutation, long devices, long jobs) {
        @Override public String toString() { return name; }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("nonmobile fresh", "", 1, 1),
                new Case("ten minutes inclusive", heartbeat("2026-09-30 11:50:00"), 1, 1),
                new Case("ten minutes plus one microsecond", heartbeat("2026-09-30 11:49:59.999999"), 0, 0),
                new Case("null heartbeat", "UPDATE nx_user_device_runtime SET heartbeat_at=NULL", 0, 0),
                new Case("future one microsecond", heartbeat("2026-09-30 12:00:00.000001"), 0, 0),
                new Case("explicit offline fresh", runtime("online_status='OFFLINE'"), 0, 0),
                new Case("runtime error fresh", runtime("online_status='ERROR'"), 0, 0),
                new Case("normalized online", runtime("online_status=' online '"), 1, 1),
                new Case("missing runtime", "DELETE FROM nx_user_device_runtime", 0, 0),
                new Case("deleted runtime", runtime("is_deleted=1"), 0, 0),
                new Case("inactive asset", device("status='INACTIVE'"), 0, 0),
                new Case("inventory asset", device("status='INVENTORY'"), 0, 0),
                new Case("unactivated", device("activated_at=NULL"), 0, 0),
                new Case("deactivated", device("deactivated_at=NOW(6)"), 0, 0),
                new Case("pending deactivate", device("pending_deactivate=1"), 0, 0),
                new Case("unowned", device("ownership_status='UNOWNED'"), 0, 0),
                new Case("deleted asset", device("is_deleted=1"), 0, 0),
                new Case("inactive user", "UPDATE nx_user SET status='INACTIVE'", 0, 0),
                new Case("deleted user", "UPDATE nx_user SET is_deleted=1", 0, 0),
                new Case("sandbox excluded from production", "UPDATE nx_user SET sandbox=1", 0, 0),
                new Case("busy activated asset", device("status='BUSY'"), 1, 1),
                new Case("running activated asset", device("status='RUNNING'"), 1, 1),
                new Case("offline lifecycle but effective online", device("status='OFFLINE'"), 1, 1),
                new Case("nonmobile ignores phone health", runtime("battery_level=19,network_reachable=0"), 1, 1),
                new Case("task source isolated", task("source_environment='SANDBOX'"), 1, 0),
                new Case("deleted task", task("is_deleted=1"), 1, 0),
                new Case("completed task", task("status='COMPLETED'"), 1, 0),
                new Case("assigned task", task("status='ASSIGNED'"), 1, 1),
                new Case("claimed task", task("status='CLAIMED'"), 1, 1),
                new Case("processing task", task("status='PROCESSING'"), 1, 1),
                new Case("phone 120 seconds inclusive and battery 20", phoneHeartbeat("2026-09-30 11:58:00"), 1, 1),
                new Case("phone 120 seconds plus one microsecond", phoneHeartbeat("2026-09-30 11:57:59.999999"), 0, 0),
                new Case("mobile battery below 20", device("device_type='MOBILE'")
                        + ";" + runtime("battery_level=19"), 0, 0),
                new Case("mobile no network", device("device_type='MOBILE'")
                        + ";" + runtime("network_reachable=0"), 0, 0),
                new Case("phone explicit offline", device("device_type='PHONE'")
                        + ";" + runtime("online_status='OFFLINE'"), 0, 0),
                new Case("phone null heartbeat", device("device_type='PHONE'")
                        + ";" + runtime("heartbeat_at=NULL"), 0, 0),
                new Case("mobile paused task stays globally online", device("device_type='MOBILE'")
                        + ";" + task("paused_at=NOW(6)"), 1, 0),
                new Case("mobile future preserves existing 120s contract", device("device_type='MOBILE'")
                        + ";" + heartbeat("2026-09-30 12:00:00.000001"), 1, 1),
                new Case("nonmobile paused task preserves existing contract", task("paused_at=NOW(6)"), 1, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void effectiveTelemetryAndEligibilityDriveAllThreeActualProjections(Case testCase) {
        for (String mutation : testCase.mutation().split(";")) {
            if (!mutation.isBlank()) jdbc.execute(mutation);
        }
        assertProjection(false, "PRODUCTION", testCase.devices(), testCase.jobs());
    }

    @Test
    void freshHeartbeatRecoversReadsWithoutAdvancingOrSettlingTheTask() {
        jdbc.execute(heartbeat("2026-09-30 11:49:59.999999"));
        assertProjection(false, "PRODUCTION", 0, 0);
        jdbc.execute(heartbeat("2026-09-30 12:00:00"));
        assertProjection(false, "PRODUCTION", 1, 1);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE id=70", String.class))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM nx_compute_task WHERE id=70", String.class))
                .isNull();
    }

    @Test
    void sandboxAndTaskSourceAreIndependentExistingBoundaries() {
        jdbc.execute("UPDATE nx_user SET sandbox=1");
        jdbc.execute(task("source_environment='SANDBOX'"));
        assertProjection(false, "PRODUCTION", 0, 0);
        assertProjection(true, "SANDBOX", 1, 1);
        assertProjection(true, "PRODUCTION", 1, 0);
    }

    @Test
    void zeroDurationTaskCountsButDoesNotInventAnEarningRate() {
        jdbc.execute(task("required_seconds=0"));
        assertThat(mapper.onGrid("PRODUCTION", false).activeJobs()).isEqualTo(1L);
        assertThat(mapper.onGrid("PRODUCTION", false).perSecUsdt()).isNull();
    }

    private void assertProjection(boolean sandbox, String environment, long devices, long jobs) {
        var before = jdbc.queryForList("SELECT * FROM nx_compute_task ORDER BY id");
        Long active = mapper.globalActiveDevices(sandbox);
        var clients = mapper.onGridClients(sandbox);
        var grid = mapper.onGrid(environment, sandbox);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(active).as("global online devices").isEqualTo(devices);
        softly.assertThat(clients.stream().mapToLong(row -> row.gpus()).sum()).as("client online GPUs")
                .isEqualTo(devices);
        softly.assertThat(grid.activeJobs()).as("eligible live jobs").isEqualTo(jobs);
        if (jobs == 0) softly.assertThat(grid.perSecUsdt()).as("empty estimated rate").isNull();
        else softly.assertThat(grid.perSecUsdt()).as("estimated USDT rate").isEqualByComparingTo("0.125");
        softly.assertThat(jdbc.queryForList("SELECT * FROM nx_compute_task ORDER BY id"))
                .as("projection must not mutate task state or rewards").isEqualTo(before);
        softly.assertAll();
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox BOOLEAN,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_user_device (id BIGINT PRIMARY KEY,user_id BIGINT,device_type VARCHAR(32),"
                + "ownership_status VARCHAR(32),status VARCHAR(32),activated_at DATETIME(6),deactivated_at DATETIME(6),"
                + "pending_deactivate INT,is_deleted INT,dc_location VARCHAR(32),gpu_model VARCHAR(64))");
        jdbc.execute("CREATE TABLE nx_user_device_runtime (user_device_id BIGINT PRIMARY KEY,online_status VARCHAR(32),"
                + "heartbeat_at DATETIME(6),battery_level INT,network_reachable INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_compute_datacenter (dc_location VARCHAR(32) PRIMARY KEY,display_name VARCHAR(64),"
                + "location VARCHAR(64),is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_compute_task (id BIGINT PRIMARY KEY,user_device_id BIGINT,user_id BIGINT,"
                + "source_environment VARCHAR(32),status VARCHAR(32),reward_usdt DECIMAL(18,6),required_seconds INT,"
                + "paused_at DATETIME(6),is_deleted INT,task_no VARCHAR(32),client_name VARCHAR(64),created_at DATETIME(6),"
                + "updated_at DATETIME(6),completed_at DATETIME(6),client_observed_at DATETIME(6) GENERATED ALWAYS AS "
                + "(COALESCE(completed_at,updated_at,created_at)) VIRTUAL)");
    }

    private static String normalizePath(String path) { return path.replace('\\', '/').replaceAll("/+$", ""); }
    private static String runtime(String change) { return "UPDATE nx_user_device_runtime SET " + change; }
    private static String device(String change) { return "UPDATE nx_user_device SET " + change; }
    private static String task(String change) { return "UPDATE nx_compute_task SET " + change; }
    private static String heartbeat(String value) { return runtime("heartbeat_at='" + value + "'"); }
    private static String phoneHeartbeat(String value) {
        return device("device_type='PHONE'") + ";" + runtime("battery_level=20") + ";" + heartbeat(value);
    }
}
