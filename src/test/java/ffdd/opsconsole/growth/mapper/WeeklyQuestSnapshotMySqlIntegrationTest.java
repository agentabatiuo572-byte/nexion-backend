package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.growth.application.WeeklyQuestSnapshotService;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.Header;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in real mapper/transaction regression, using the existing UUID-only MySQL fixture contract. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeeklyQuestSnapshotMySqlIntegrationTest extends WeeklyQuestSnapshotMySqlFixture {
    private static final String KEY = "WEEK:2026-W42";
    private static final String CODE = "weekly_t2_browse_store";
    private static final String EVENT = "H3_STOREFRONT_THREE_PRODUCTS_VIEWED";
    private static final LocalDateTime EVENT_AT = LocalDateTime.of(2026, 10, 16, 8, 0);
    @Test
    void frozenDefinitionStillListsCompletesAndExposesOriginalClaimRewardAfterLiveChanges() {
        seedDefinition();
        Header header = transaction.execute(status -> service(mapper).beforeConfigurationChange());
        jdbc.update("UPDATE nx_mission SET status=0,reward_points=999,mission_name='changed',action_route='/changed'");
        jdbc.update("UPDATE nx_growth_quest_event_binding SET status=0,event_type='CHANGED',producer='CHANGED'");
        jdbc.update("INSERT INTO nx_config_item(config_key,config_value,status) VALUES ('growth.content.localized',?,1)",
                "{\"mission\":{\"weekly_t2_browse_store\":{\"en\":{\"name\":\"changed localized\"}}}}");

        Header reread = mapper.find(KEY);
        assertThat(reread.questBonusMultiplier()).isEqualByComparingTo("2");
        assertThat(reread.rhythmMonth()).isEqualTo(3);
        assertThat(reread.requiredTaskCount()).isEqualTo(1);
        assertThat(mapper.state(KEY, 3778L, "en")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("questCode", CODE).containsEntry("name", "Original browse task")
                    .containsEntry("actionRoute", "/pages/tabs/store").containsEntry("status", "PENDING")
                    .containsEntry("instanceKey", KEY);
            assertThat(new BigDecimal(row.get("rewardNex").toString())).isEqualByComparingTo("10");
        });
        assertThat(mapper.bindings(KEY, EVENT)).singleElement().satisfies(binding -> {
            assertThat(binding.eventType()).isEqualTo(EVENT);
            assertThat(binding.producer()).isEqualTo("SYSTEM");
        });
        assertThat(mapper.mission(KEY, CODE, EVENT_AT)).isNotNull();
        assertThat(mapper.mission(KEY, CODE, header.eligibleUntil())).isNull();
        jdbc.update("INSERT INTO nx_user_mission(user_id,mission_id,instance_key,mission_status) VALUES(3778,1,?,'CLAIMABLE')", KEY);
        var reward = transaction.execute(status -> mapper.lockReward(KEY, CODE, 3778L));
        assertThat(reward).isNotNull();
        assertThat(reward.rewardNex()).isEqualByComparingTo("10");
        assertThat(reward.rewardNex().multiply(reread.questBonusMultiplier())).isEqualByComparingTo("20");
        // This tests the claim input projection, not wallet credit or claim CAS.
    }

    @Test
    void persistedEmptyWeekIsNotRefilledByLaterPublication() {
        Header empty = transaction.execute(status -> service(mapper).current());
        seedDefinition();
        Header afterPublication = transaction.execute(status -> service(mapper).beforeConfigurationChange());
        assertThat(empty.requiredTaskCount()).isZero();
        assertThat(afterPublication.requiredTaskCount()).isZero();
        assertThat(mapper.state(KEY, 3778L, "en")).isEmpty();
        assertThat(mapper.bindings(KEY, EVENT)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_weekly_definition", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentFirstReadAndPublicationUseOneCommittedWinnerUnderRepeatableRead() throws Exception {
        seedDefinition();
        CountDownLatch bothInitialReads = new CountDownLatch(2);
        WeeklyQuestSnapshotMapper coordinated = mock(WeeklyQuestSnapshotMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doAnswer(call -> {
            Header result = mapper.find(call.getArgument(0));
            assertThat(result).isNull();
            bothInitialReads.countDown();
            assertThat(bothInitialReads.await(10, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(coordinated).find(anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var read = executor.submit(() -> transaction.execute(status -> service(coordinated).current()));
            var publish = executor.submit(() -> transaction.execute(status -> service(coordinated).beforeConfigurationChange()));
            Header first = read.get(30, TimeUnit.SECONDS);
            Header second = publish.get(30, TimeUnit.SECONDS);
            assertThat(first.instanceKey()).isEqualTo(second.instanceKey()).isEqualTo(KEY);
            assertThat(first.requiredTaskCount()).isEqualTo(second.requiredTaskCount()).isEqualTo(1);
            assertThat(first.questBonusMultiplier()).isEqualByComparingTo(second.questBonusMultiplier());
            for (String table : List.of("nx_growth_weekly_definition", "nx_growth_weekly_definition_item", "nx_growth_weekly_definition_binding")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private WeeklyQuestSnapshotService service(WeeklyQuestSnapshotMapper target) {
        GrowthRhythmFacade rhythm = () -> new GrowthRhythmSnapshot(12, 3, "P2", 50, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.15"), 30, new BigDecimal("5000"),
                new BigDecimal("2"), false, List.of("H1.rhythm.currentMonth"));
        return new WeeklyQuestSnapshotService(target, rhythm,
                Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
    }

    private void seedDefinition() {
        jdbc.update("INSERT INTO nx_mission VALUES(1,?,'Original browse task','STORE','/pages/tabs/store',10,'WEEKLY_T2',1,0)", CODE);
        jdbc.update("INSERT INTO nx_growth_quest_event_binding VALUES(11,'browse-binding','SYSTEM',?,?,'user_id',1,0)", EVENT, CODE);
    }

}

/** Shared fixture only; contains no test methods, production access, or credential persistence. */
class WeeklyQuestSnapshotMySqlFixture {
    private static final Pattern OWNED_SCHEMA = Pattern.compile("^nx_h3_weekly_snapshot_test_[0-9a-f]{32}$");
    private Connection admin;
    private String fixtureSchema;
    private boolean fixtureCreated;
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    WeeklyQuestSnapshotMapper mapper;
    DataSource fixtureSource;
    SqlSessionTemplate fixtureSessions;

    @BeforeAll
    void createFixture() throws Exception {
        String serverUrl = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        H3DayOneSnapshotMapperMySqlIntegrationTest.requireServerUrlWithoutCatalog(serverUrl);
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        admin = DriverManager.getConnection(serverUrl, username, password);
        fixtureSchema = "nx_h3_weekly_snapshot_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteSchema() + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            fixtureCreated = true;
            System.out.println("H3_WEEKLY_FIXTURE_CREATE=" + fixtureSchema);
        }
        int query = serverUrl.indexOf('?');
        String fixtureUrl = query < 0 ? serverUrl + fixtureSchema
                : serverUrl.substring(0, query) + fixtureSchema + serverUrl.substring(query);
        DataSource dataSource = fixtureDataSource(fixtureUrl, username, password);
        fixtureSource=dataSource;
        jdbc = new JdbcTemplate(dataSource);
        createDependencies();
        Path root = Path.of(System.getProperty("nexion.repo.root", ".")).toAbsolutePath().normalize();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    root.resolve("scripts/migrations/20261016_h3_weekly_definition_snapshot.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    root.resolve("scripts/migrations/20261018_h3_regional_weekly_period.sql")));
        }
        Configuration configuration = new Configuration(new Environment("h3-weekly-snapshot-it",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WeeklyQuestSnapshotMapper.class);
        fixtureSessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = fixtureSessions.getMapper(WeeklyQuestSnapshotMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @BeforeEach
    void clearFixtureRows() {
        for (String table : List.of("nx_growth_account_weekly_period","nx_growth_weekly_definition_binding", "nx_growth_weekly_definition_item",
                "nx_growth_weekly_definition", "nx_user_mission", "nx_growth_quest_event_binding", "nx_mission", "nx_config_item", "nx_admin_operation_mutex")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @AfterAll
    void dropOnlyCreatedUuidFixture() throws Exception {
        if (admin == null) return;
        try {
            if (fixtureCreated) {
                try (var statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE " + quoteSchema());
                    System.out.println("H3_WEEKLY_FIXTURE_DROP=" + fixtureSchema);
                }
            }
        } finally { admin.close(); }
    }

    private void createDependencies() {
        jdbc.execute("CREATE TABLE nx_admin_operation_mutex(lock_key VARCHAR(64) PRIMARY KEY,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_mission(id BIGINT PRIMARY KEY,mission_code VARCHAR(64),mission_name VARCHAR(128),"
                + "mission_category VARCHAR(32),action_route VARCHAR(255),reward_points INT,mission_type VARCHAR(32),"
                + "status TINYINT,is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_growth_quest_event_binding(id BIGINT PRIMARY KEY,binding_code VARCHAR(64),producer VARCHAR(32),"
                + "event_type VARCHAR(128),quest_code VARCHAR(64),user_id_field VARCHAR(64),status TINYINT,is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(128) PRIMARY KEY,config_value LONGTEXT,status TINYINT,"
                + "is_deleted TINYINT DEFAULT 0,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_user_mission(user_id BIGINT,mission_id BIGINT,instance_key VARCHAR(64),mission_status VARCHAR(32),"
                + "is_deleted TINYINT DEFAULT 0,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_id,mission_id,instance_key)) ENGINE=InnoDB");
    }

    private DataSource fixtureDataSource(String url, String username, String password) {
        return new DelegatingDataSource(new DriverManagerDataSource(url, username, password)) {
            @Override public Connection getConnection() throws java.sql.SQLException { return validate(super.getConnection()); }
            @Override public Connection getConnection(String user, String secret) throws java.sql.SQLException {
                return validate(super.getConnection(user, secret));
            }
        };
    }

    private Connection validate(Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+08:00'");
            try (var result = statement.executeQuery("SELECT DATABASE(),@@session.time_zone")) {
                if (!result.next() || !fixtureSchema.equals(result.getString(1)) || !"+08:00".equals(result.getString(2))) {
                    throw new java.sql.SQLException("H3_FIXTURE_CONNECTION_SCOPE_OR_ZONE_INVALID");
                }
            }
        } catch (java.sql.SQLException ex) { connection.close(); throw ex; }
        return connection;
    }

    private String quoteSchema() {
        if (fixtureSchema == null || !OWNED_SCHEMA.matcher(fixtureSchema).matches()) throw new IllegalStateException("UNOWNED_FIXTURE_SCHEMA");
        return "`" + fixtureSchema + "`";
    }
}
