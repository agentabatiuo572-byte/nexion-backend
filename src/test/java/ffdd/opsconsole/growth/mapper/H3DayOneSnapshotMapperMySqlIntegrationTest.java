package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.growth.facade.DayOneInstanceFacade.DayOneInstanceSnapshot;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneDefinitionBinding;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in isolated MySQL regression. It rejects a server URL with a
 * selected catalog; every JDBC/MyBatis fixture connection proves SELECT DATABASE()
 * equals the freshly created UUID schema.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H3DayOneSnapshotMapperMySqlIntegrationTest {
    private static final Pattern OWNED_SCHEMA =
            Pattern.compile("^nx_h3_day_one_instance_test_[0-9a-f]{32}$");
    private static final Pattern LOOPBACK_SERVER_URL = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final LocalDateTime FIXTURE_ENTERED_AT = LocalDateTime.ofInstant(
            Instant.parse("2026-09-09T02:30:15Z"), DateTimeFormatConfig.BUSINESS_ZONE);

    private Connection admin;
    private String fixtureSchema;
    private boolean fixtureCreated;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private DayOneInstanceMapper dayOne;
    private AppGrowthEngagementMapper app;

    @BeforeAll
    void createFixture() throws Exception {
        String serverUrl = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        requireServerUrlWithoutCatalog(serverUrl);
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");

        admin = DriverManager.getConnection(serverUrl, username, password);
        fixtureSchema = "nx_h3_day_one_instance_test_" + UUID.randomUUID().toString().replace("-", "");
        requireOwnedSchema(fixtureSchema);
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + quote(fixtureSchema)
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            fixtureCreated = true;
            System.out.println("H3_FIXTURE_CREATE=" + fixtureSchema);
        }

        DataSource dataSource = fixtureDataSource(fixtureUrl(serverUrl, fixtureSchema), username, password);
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureSchema);
        runFormalMigration(dataSource);
        createFixtureDependencyTables();

        Configuration configuration = new Configuration(new Environment("h3-day-one-snapshot-it",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DayOneInstanceMapper.class);
        configuration.addMapper(AppGrowthEngagementMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder()
                .build(configuration));
        dayOne = template.getMapper(DayOneInstanceMapper.class);
        app = template.getMapper(AppGrowthEngagementMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    void dropOnlyCreatedUuidFixture() throws Exception {
        if (admin == null) return;
        try {
            if (fixtureCreated && isOwnedSchema(fixtureSchema)) {
                try (var statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE " + quote(fixtureSchema));
                    System.out.println("H3_FIXTURE_DROP=" + fixtureSchema);
                }
            }
        } finally {
            admin.close();
        }
    }

    @Test
    void actualMapperMapsNullableEmptyAndCompleteSnapshotHeaders() {
        LocalDateTime enteredAt = FIXTURE_ENTERED_AT;
        seedUser(1101L, enteredAt);
        seedUser(1102L, enteredAt);

        assertThat(dayOne.insertInstanceIfAbsent(empty(1101L, "DAY_ONE:EMPTY", enteredAt))).isOne();
        DayOneInstanceSnapshot empty = dayOne.lockExistingInstance(1101L, "DAY_ONE:EMPTY");
        assertThat(empty.snapshotStatus()).isEqualTo("EMPTY");
        assertThat(empty.requiredTaskCount()).isZero();
        assertThat(empty.triReward()).isNull();
        assertThat(empty.questBonusMultiplier()).isNull();
        assertThat(empty.rhythmMonth()).isNull();

        DayOneInstanceSnapshot stored = createSevenItemSnapshot(1102L, "DAY_ONE:FULL", enteredAt);
        var read = app.findLatestDayOneSnapshot(1102L);
        assertThat(read.instanceId()).isEqualTo(stored.id());
        assertThat(read.snapshotStatus()).isEqualTo("SNAPSHOT");
        assertThat(read.requiredTaskCount()).isEqualTo(7);
        assertThat(read.triReward()).isEqualTo("500 / 200 / 0 NEX");
        assertThat(read.questBonusMultiplier()).isEqualByComparingTo("2.000000");
        assertThat(read.rhythmMonth()).isEqualTo(3);
    }

    @Test
    void sevenFrozenItemsRemainReadableWhenCurrentDefinitionsAndConfigAreChanged() {
        LocalDateTime enteredAt = FIXTURE_ENTERED_AT;
        seedUser(1201L, enteredAt);
        DayOneInstanceSnapshot stored = createSevenItemSnapshot(1201L, "DAY_ONE:FROZEN", enteredAt);

        jdbc.update("UPDATE nx_mission SET status=0,mission_name='changed',mission_category='changed',"
                + "action_route='/changed',reward_points=999 WHERE mission_type='DAY_ONE'");
        jdbc.update("UPDATE nx_growth_quest_event_binding SET status=0");
        jdbc.update("UPDATE nx_config_item SET config_value='999'");

        DayOneInstanceSnapshot header = dayOne.findLatestByUserId(1201L);
        assertThat(header.requiredTaskCount()).isEqualTo(7);
        assertThat(header.triReward()).isEqualTo("500 / 200 / 0 NEX");
        assertThat(header.questBonusMultiplier()).isEqualByComparingTo("2.000000");
        assertThat(header.rhythmMonth()).isEqualTo(3);
        assertThat(dayOne.listItems(stored.id())).hasSize(7).allSatisfy(item -> {
            assertThat(item.name()).startsWith("Original ");
            assertThat(item.category()).isEqualTo("EXPLORE");
            assertThat(item.actionRoute()).startsWith("/pages/");
            assertThat(item.rewardPoints()).isEqualTo(10);
        });

        List<Map<String, Object>> state = app.dayOneSnapshotState(1201L, stored.id());
        assertThat(state).hasSize(7).allSatisfy(row -> {
            assertThat(row.get("name")).asString().startsWith("Original ");
            assertThat(row.get("category")).isEqualTo("EXPLORE");
            assertThat(row.get("actionRoute")).asString().startsWith("/pages/");
            assertThat(row.get("instanceKey")).isEqualTo("DAY_ONE:FROZEN");
            assertThat(row.get("eligibleFrom")).isEqualTo("2026-09-09T10:30:15+08:00");
            assertThat(row.get("eligibleUntil")).isEqualTo("2026-09-12T10:30:15+08:00");
        });
    }

    @Test
    void concurrentSnapshotClaimCasTransitionsTheSevenFrozenMembersOnlyOnce() throws Exception {
        LocalDateTime enteredAt = FIXTURE_ENTERED_AT;
        seedUser(1301L, enteredAt);
        DayOneInstanceSnapshot stored = createSevenItemSnapshot(1301L, "DAY_ONE:CAS", enteredAt);
        jdbc.update("UPDATE nx_user_mission SET mission_status='CLAIMABLE'"
                + " WHERE user_id=? AND instance_key=?", 1301L, stored.instanceKey());

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> attempts = List.of(
                    () -> claimAfterStart(start, 1301L, stored),
                    () -> claimAfterStart(start, 1301L, stored));
            var futures = attempts.stream().map(executor::submit).toList();
            start.countDown();
            int total = futures.stream().mapToInt(future -> {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            }).sum();

            assertThat(total).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_mission"
                    + " WHERE user_id=? AND instance_key=? AND mission_status='CLAIMED'",
                    Integer.class, 1301L, stored.instanceKey())).isEqualTo(7);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int claimAfterStart(CountDownLatch start, Long userId, DayOneInstanceSnapshot snapshot) throws Exception {
        start.await();
        Integer changed = transaction.execute(status -> {
            assertThat(app.lockDayOneSnapshotGroup(userId, snapshot.id(), snapshot.instanceKey())).hasSize(7);
            return app.claimDayOneSnapshotGroup(userId, snapshot.id(), snapshot.instanceKey());
        });
        return changed == null ? 0 : changed;
    }

    private DayOneInstanceSnapshot createSevenItemSnapshot(Long userId, String key, LocalDateTime enteredAt) {
        seedLiveDefinitions();
        List<Long> ids = dayOne.lockActiveDayOneDefinitionIds();
        List<DayOneDefinitionBinding> bindings = dayOne.lockActiveDayOneDefinitionBindings();
        assertThat(ids).hasSize(7);
        assertThat(bindings).hasSize(7);
        DayOneDefinitionBinding first = bindings.get(0);
        assertThat(first.sourceMissionId()).isEqualTo(1L);
        assertThat(first.sourceBindingId()).isEqualTo(1L);
        assertThat(first.questCode()).isEqualTo("DAY_1");
        assertThat(first.name()).isEqualTo("Original 1");
        assertThat(first.bindingCode()).isEqualTo("BIND_1");
        assertThat(first.eventType()).isEqualTo("EVENT_1");

        assertThat(dayOne.insertInstanceIfAbsent(snapshot(userId, key, enteredAt))).isOne();
        DayOneInstanceSnapshot stored = dayOne.lockExistingInstance(userId, key);
        int ordinal = 0;
        for (DayOneDefinitionBinding binding : bindings) {
            assertThat(dayOne.insertItem(stored.id(), binding.sourceMissionId(), binding.questCode(), binding.name(),
                    binding.category(), binding.actionRoute(), binding.rewardPoints(), ++ordinal)).isOne();
            assertThat(dayOne.insertBinding(stored.id(), binding.sourceMissionId(), binding.sourceBindingId(),
                    binding.bindingCode(), binding.producer(), binding.eventType(), binding.userIdField())).isOne();
            jdbc.update("INSERT INTO nx_user_mission(user_id,mission_id,instance_key,mission_status,is_deleted)"
                    + " VALUES (?,?,?,'PENDING',0)", userId, binding.sourceMissionId(), key);
        }
        return dayOne.lockExistingInstance(userId, key);
    }

    private void seedLiveDefinitions() {
        for (int index = 1; index <= 7; index++) {
            jdbc.update("INSERT INTO nx_mission(id,mission_code,mission_name,mission_category,action_route,reward_points,"
                            + "mission_type,status,is_deleted) VALUES (?,?,?,?,?,10,'DAY_ONE',1,0)"
                            + " ON DUPLICATE KEY UPDATE mission_code=VALUES(mission_code),mission_name=VALUES(mission_name),"
                            + "mission_category=VALUES(mission_category),action_route=VALUES(action_route),reward_points=10,"
                            + "mission_type='DAY_ONE',status=1,is_deleted=0",
                    (long) index, "DAY_" + index, "Original " + index, "EXPLORE", "/pages/task/" + index);
            jdbc.update("INSERT INTO nx_growth_quest_event_binding(id,binding_code,producer,event_type,quest_code,"
                            + "user_id_field,status,is_deleted) VALUES (?,?,?,?,?,'user_id',1,0)"
                            + " ON DUPLICATE KEY UPDATE binding_code=VALUES(binding_code),producer=VALUES(producer),"
                            + "event_type=VALUES(event_type),quest_code=VALUES(quest_code),status=1,is_deleted=0",
                    (long) index, "BIND_" + index, "SYSTEM", "EVENT_" + index, "DAY_" + index);
        }
        jdbc.update("INSERT INTO nx_config_item(config_key,config_value,status,is_deleted)"
                        + " VALUES ('growth.quest.day_one.tri_reward','500 / 200 / 0 NEX',1,0)"
                        + " ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),status=1,is_deleted=0");
    }

    private void seedUser(Long userId, LocalDateTime enteredAt) {
        jdbc.update("INSERT INTO nx_user(id,status,created_at,is_deleted) VALUES (?,'ACTIVE',?,0)",
                userId, enteredAt);
    }

    private static DayOneInstanceSnapshot empty(Long userId, String key, LocalDateTime enteredAt) {
        return new DayOneInstanceSnapshot(null, userId, key, "EMPTY", enteredAt, 72, 24,
                enteredAt.plusHours(72), null, null, null, 0, "e".repeat(64));
    }

    private static DayOneInstanceSnapshot snapshot(Long userId, String key, LocalDateTime enteredAt) {
        return new DayOneInstanceSnapshot(null, userId, key, "SNAPSHOT", enteredAt, 72, 24,
                enteredAt.plusHours(72), "500 / 200 / 0 NEX", new BigDecimal("2.000000"), 3, 7, "a".repeat(64));
    }

    private void runFormalMigration(DataSource dataSource) throws Exception {
        Path root = Path.of(System.getProperty("nexion.repo.root", ".")).toAbsolutePath().normalize();
        Path migration = root.resolve("scripts/migrations/20260909_h3_day_one_instance_snapshot.sql");
        if (!Files.isRegularFile(migration)) throw new IllegalStateException("H3_DAY_ONE_MIGRATION_NOT_FOUND:" + migration);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
        }
    }

    private void createFixtureDependencyTables() {
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,status VARCHAR(32) NOT NULL,"
                + "created_at DATETIME NOT NULL,is_deleted TINYINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE nx_mission (id BIGINT PRIMARY KEY,mission_code VARCHAR(64) NOT NULL,"
                + "mission_name VARCHAR(128) NOT NULL,mission_category VARCHAR(32) NOT NULL,"
                + "action_route VARCHAR(255) NOT NULL,reward_points INT NOT NULL,mission_type VARCHAR(32) NOT NULL,"
                + "status TINYINT NOT NULL,is_deleted TINYINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE nx_growth_quest_event_binding (id BIGINT PRIMARY KEY,binding_code VARCHAR(48) NOT NULL UNIQUE,"
                + "producer VARCHAR(32) NOT NULL,event_type VARCHAR(128) NOT NULL,quest_code VARCHAR(64) NOT NULL,"
                + "user_id_field VARCHAR(64) NOT NULL,status TINYINT NOT NULL,is_deleted TINYINT NOT NULL DEFAULT 0)"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE nx_config_item (config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(128) NOT NULL,"
                + "status TINYINT NOT NULL,is_deleted TINYINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE nx_user_mission (user_id BIGINT NOT NULL,mission_id BIGINT NOT NULL,"
                + "instance_key VARCHAR(64) NOT NULL,mission_status VARCHAR(32) NOT NULL,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,is_deleted TINYINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(user_id,mission_id,instance_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private static String fixtureUrl(String serverUrl, String schema) {
        requireOwnedSchema(schema);
        int query = serverUrl.indexOf('?');
        String base = query >= 0 ? serverUrl.substring(0, query) : serverUrl;
        String suffix = query >= 0 ? serverUrl.substring(query) : "";
        return base + schema + suffix;
    }

    static void requireServerUrlWithoutCatalog(String url) {
        if (url == null || !LOOPBACK_SERVER_URL.matcher(url).matches()) {
            throw new IllegalStateException("NEXION_TEST_DB_SERVER_URL_MUST_BE_LOOPBACK_WITHOUT_A_CATALOG");
        }
    }

    private DataSource fixtureDataSource(String url, String username, String password) {
        DriverManagerDataSource raw = new DriverManagerDataSource(url, username, password);
        return new DelegatingDataSource(raw) {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
                return initializeFixtureConnection(super.getConnection());
            }

            @Override
            public Connection getConnection(String requestedUser, String requestedPassword) throws java.sql.SQLException {
                return initializeFixtureConnection(super.getConnection(requestedUser, requestedPassword));
            }
        };
    }

    private Connection initializeFixtureConnection(Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+08:00'");
            try (var result = statement.executeQuery("SELECT DATABASE(),@@session.time_zone")) {
                if (!result.next() || !fixtureSchema.equals(result.getString(1))
                        || !"+08:00".equals(result.getString(2))) {
                    throw new java.sql.SQLException("H3_FIXTURE_CONNECTION_SCOPE_OR_ZONE_INVALID");
                }
            }
        } catch (java.sql.SQLException ex) {
            connection.close();
            throw ex;
        }
        return connection;
    }

    private static String quote(String identifier) {
        requireOwnedSchema(identifier);
        return String.valueOf((char) 96) + identifier + (char) 96;
    }

    private static void requireOwnedSchema(String schema) {
        if (!isOwnedSchema(schema)) throw new IllegalStateException("UNOWNED_FIXTURE_SCHEMA");
    }

    private static boolean isOwnedSchema(String schema) {
        return schema != null && OWNED_SCHEMA.matcher(schema).matches();
    }
}
