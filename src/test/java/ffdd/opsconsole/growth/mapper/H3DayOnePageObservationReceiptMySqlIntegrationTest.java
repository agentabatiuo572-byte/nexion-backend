package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.growth.application.H3DayOnePageObservationService;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.springframework.aop.framework.ProxyFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Real MySQL proof for the Day One source gate. It creates a random owned
 * schema and never reads or mutates a Nexion business database.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H3DayOnePageObservationReceiptMySqlIntegrationTest {
    private static final Pattern OWNED_SCHEMA =
            Pattern.compile("^nx_h3_day_one_receipt_test_[0-9a-f]{32}$");
    private static final Pattern LOOPBACK_SERVER_URL = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private Connection admin;
    private String database;
    private boolean fixtureCreated;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private H3DayOnePageObservationReceiptMapper mapper;
    private QuestCompletionFactMapper missionMapper;
    private QuestCanonicalEventBindingMapper bindingMapper;
    private EventOutboxMapper outboxMapper;
    private DataSource dataSource;

    @BeforeAll
    void createOwnedFixtureDatabase() throws Exception {
        String sourceUrl = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        requireServerUrlWithoutCatalog(sourceUrl);
        admin = DriverManager.getConnection(sourceUrl, System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        database = "nx_h3_day_one_receipt_test_" + UUID.randomUUID().toString().replace("-", "");
        requireOwnedSchema(database);
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + quote(database)
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            fixtureCreated = true;
        }
        dataSource = fixtureDataSource(fixtureUrl(sourceUrl, database),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"), System.getenv("NEXION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        jdbc.execute("""
                CREATE TABLE nx_growth_day_one_page_observation_receipt (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
                  instance_key VARCHAR(64) NOT NULL, surface VARCHAR(16) NOT NULL,
                  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
                  is_deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_h3_day_one_page_observation_receipt (user_id,instance_key,surface)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_event_outbox (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(96) NOT NULL UNIQUE,
                  aggregate_type VARCHAR(64) NOT NULL DEFAULT 'TEST', aggregate_id VARCHAR(128) NOT NULL DEFAULT 'TEST',
                  event_type VARCHAR(96) NOT NULL DEFAULT 'TEST', event_name VARCHAR(128) NULL, family_key VARCHAR(32) NULL,
                  event_ts DATETIME(3) NULL, phase VARCHAR(32) NULL, account_age_months INT NOT NULL DEFAULT 0,
                  cohort VARCHAR(16) NULL, is_server_authoritative TINYINT NOT NULL DEFAULT 1,
                  schema_revision INT NULL, schema_registered TINYINT NOT NULL DEFAULT 0,
                  analytics_event TINYINT NOT NULL DEFAULT 0, payload JSON NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'PENDING', retry_count INT NOT NULL DEFAULT 0,
                  next_retry_at DATETIME NULL, published_at DATETIME NULL, last_error VARCHAR(512) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_user (
                  id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL, created_at DATETIME(3) NOT NULL,
                  sandbox TINYINT NOT NULL DEFAULT 0, is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_mission (
                  id BIGINT PRIMARY KEY, mission_code VARCHAR(64) NOT NULL, mission_type VARCHAR(32) NOT NULL,
                  status TINYINT NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_config_item (
                  config_key VARCHAR(128) PRIMARY KEY, config_value VARCHAR(128) NOT NULL,
                  status TINYINT NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_user_mission (
                  user_id BIGINT NOT NULL, mission_id BIGINT NOT NULL, instance_key VARCHAR(64) NOT NULL,
                  mission_status VARCHAR(32) NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
                  PRIMARY KEY(user_id,mission_id,instance_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_growth_quest_event_binding (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, binding_code VARCHAR(48) NOT NULL UNIQUE,
                  producer VARCHAR(32) NOT NULL, event_type VARCHAR(128) NOT NULL,
                  quest_code VARCHAR(64) NOT NULL, user_id_field VARCHAR(64) NOT NULL,
                  status TINYINT NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Configuration configuration = new Configuration(new Environment("h3-day-one-receipt",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(H3DayOnePageObservationReceiptMapper.class);
        configuration.addMapper(QuestCompletionFactMapper.class);
        configuration.addMapper(QuestCanonicalEventBindingMapper.class);
        configuration.addMapper(EventOutboxMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = template.getMapper(H3DayOnePageObservationReceiptMapper.class);
        missionMapper = template.getMapper(QuestCompletionFactMapper.class);
        bindingMapper = template.getMapper(QuestCanonicalEventBindingMapper.class);
        outboxMapper = template.getMapper(EventOutboxMapper.class);
    }

    @AfterAll
    void dropOwnedFixtureDatabase() throws Exception {
        if (admin == null) return;
        try {
            if (fixtureCreated && isOwnedSchema(database)) {
                try (var statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE " + quote(database));
                }
            }
        } finally {
            admin.close();
        }
    }

    @Test
    void concurrentSourceAttemptsWinExactlyOneDurableReceipt() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> attempts = List.of(
                    () -> transaction.execute(status -> mapper.insertIfAbsent(42L, "DAY_ONE:fixture", "earn")),
                    () -> transaction.execute(status -> mapper.insertIfAbsent(42L, "DAY_ONE:fixture", "earn")));
            assertThat(executor.invokeAll(attempts).stream().mapToInt(future -> {
                try { return future.get(); } catch (Exception ex) { throw new AssertionError(ex); }
            }).sum()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_day_one_page_observation_receipt", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedPublishRollsBackItsReceiptAndOutboxInsertTogether() {
        try {
            transaction.executeWithoutResult(status -> {
                assertThat(mapper.insertIfAbsent(43L, "DAY_ONE:fixture", "store")).isOne();
                jdbc.update("INSERT INTO nx_event_outbox(event_id) VALUES (?)", "fixture-publish");
                throw new IllegalStateException("synthetic publish failure");
            });
        } catch (IllegalStateException expected) {
            // The service's @Transactional boundary must expose this same rollback behavior.
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_day_one_page_observation_receipt WHERE user_id=43", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    @Test
    void actualTransactionalObservationServiceRollsBackReceiptWhenItsOutboxPublishFails() {
        LocalDateTime now = databaseNow3();
        jdbc.update("INSERT INTO nx_user(id,status,created_at,sandbox,is_deleted) VALUES (301,'ACTIVE',?,0,0)", now);
        jdbc.update("INSERT INTO nx_mission(id,mission_code,mission_type,status,is_deleted)"
                + " VALUES (301,'visit_earn','DAY_ONE',1,0)");
        jdbc.update("INSERT INTO nx_growth_quest_event_binding(binding_code,producer,event_type,quest_code,user_id_field,status,is_deleted)"
                + " VALUES ('DAY_ONE_EARN','SYSTEM','H3_DAY_ONE_EARN_PAGE_VIEWED','visit_earn','user_id',1,0)");
        EventOutboxService failingOutbox = mock(EventOutboxService.class);
        doAnswer(call -> {
            jdbc.update("INSERT INTO nx_event_outbox(event_id) VALUES (?)", "service-publish");
            throw new IllegalStateException("synthetic publish failure");
        }).when(failingOutbox).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
        DayOneInstanceMapper snapshots = mock(DayOneInstanceMapper.class);
        QuestCompletionFactMapper snapshotMissions = mock(QuestCompletionFactMapper.class);
        when(snapshotMissions.lockActiveUser(301L)).thenReturn(301L);
        when(snapshots.listInWindowSnapshotBindings(eq(List.of(301L)), eq("H3_DAY_ONE_EARN_PAGE_VIEWED"), any()))
                .thenReturn(List.of(new DayOneSnapshotBinding(1L, 301L, "DAY_ONE:fixture", 301L,
                        "visit_earn", "DAY_ONE_EARN", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED", "user_id", "{}")));
        when(snapshotMissions.lockDayOneSnapshotMissionAt(eq(301L), eq(301L), eq("visit_earn"),
                eq("DAY_ONE:fixture"), any()))
                .thenReturn(new MissionDefinition(301L, "visit_earn", "DAY_ONE", "DAY_ONE:fixture"));
        when(snapshotMissions.attribution(301L)).thenReturn(java.util.Map.of(
                "phase", "P1", "accountAgeMonths", 0, "cohort", "2026-W36"));
        H3DayOnePageObservationService target = new H3DayOnePageObservationService(
                snapshots, snapshotMissions, mapper, failingOutbox, Clock.system(ZoneId.of("Asia/Shanghai")));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        H3DayOnePageObservationService service = (H3DayOnePageObservationService) proxy.getProxy();

        assertThatThrownBy(() -> service.observe(301L, "earn"))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic publish failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_day_one_page_observation_receipt WHERE user_id=301", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_id='service-publish'", Integer.class))
                .isZero();
    }

    @Test
    void trustedDayOneEventTimeAllowsDelayedProjectionButRejectsPreRegistrationAndFutureFacts() {
        // event_ts is written by EventOutboxMapper with NOW(3); use the same
        // database/session clock rather than a hand-normalized UTC+08 value.
        LocalDateTime serverNow = databaseNow3();
        LocalDateTime createdAt = serverNow.minusHours(25);
        jdbc.update("INSERT INTO nx_user(id,status,created_at,is_deleted) VALUES (101,'ACTIVE',?,0)", createdAt);
        jdbc.update("INSERT INTO nx_mission(id,mission_code,mission_type,status,is_deleted)"
                + " VALUES (1,'visit_earn','DAY_ONE',1,0)");
        jdbc.update("INSERT INTO nx_config_item(config_key,config_value,status,is_deleted)"
                + " VALUES ('growth.quest.day_one.eligibility_hours','24',1,0)");

        assertThat(missionMapper.lockMissionInstanceAt(101L, "visit_earn", createdAt.plusHours(23))).isNotNull();
        assertThat(missionMapper.lockMissionInstanceAt(101L, "visit_earn", createdAt.minusSeconds(1))).isNull();
        jdbc.update("INSERT INTO nx_user(id,status,created_at,sandbox,is_deleted) VALUES (102,'ACTIVE',?,0,0)",
                serverNow.minusHours(1));
        jdbc.update("INSERT INTO nx_event_outbox(event_id,aggregate_type,aggregate_id,event_type,event_ts)"
                + " VALUES ('fresh-outbox','H3_DAY_ONE_PAGE','103:fixture:earn','H3_DAY_ONE_EARN_PAGE_VIEWED',NOW(3))");
        LocalDateTime freshOutboxEventTs = outboxMapper.listByAggregate("H3_DAY_ONE_PAGE", "103:fixture:earn", 1)
                .stream().map(EventOutboxMessage::getEventTs).findFirst().orElseThrow();
        jdbc.update("INSERT INTO nx_user(id,status,created_at,sandbox,is_deleted) VALUES (103,'ACTIVE',?,0,0)",
                freshOutboxEventTs.minusHours(1));
        // A real fresh event_ts is also NOW(3), including milliseconds, so it
        // must not be rejected by a seconds-truncated current-time comparison.
        assertThat(missionMapper.lockMissionInstanceAt(103L, "visit_earn", freshOutboxEventTs)).isNotNull();
        // This user is still inside the 24-hour window, so the future rejection
        // is attributable to the same NOW(3) source-clock upper bound, not expiry.
        assertThat(missionMapper.lockMissionInstanceAt(102L, "visit_earn", serverNow.plusMinutes(1))).isNull();
    }

    private static String fixtureUrl(String sourceUrl, String database) {
        requireServerUrlWithoutCatalog(sourceUrl);
        requireOwnedSchema(database);
        int query = sourceUrl.indexOf('?');
        String base = query >= 0 ? sourceUrl.substring(0, query) : sourceUrl;
        String suffix = query >= 0 ? sourceUrl.substring(query) : "";
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + database + suffix;
    }

    static void requireServerUrlWithoutCatalog(String url) {
        if (url == null || !LOOPBACK_SERVER_URL.matcher(url).matches()) {
            throw new IllegalStateException("NEXION_TEST_DB_SERVER_URL_MUST_BE_LOOPBACK_WITHOUT_A_CATALOG");
        }
    }

    private static void requireOwnedSchema(String schema) {
        if (!isOwnedSchema(schema)) throw new IllegalStateException("UNOWNED_FIXTURE_SCHEMA");
    }

    private static boolean isOwnedSchema(String schema) {
        return schema != null && OWNED_SCHEMA.matcher(schema).matches();
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
                if (!result.next() || !database.equals(result.getString(1))
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

    private LocalDateTime databaseNow3() {
        return jdbc.query("SELECT NOW(3)", resultSet -> {
            if (!resultSet.next()) throw new IllegalStateException("NOW_3_UNAVAILABLE");
            return resultSet.getObject(1, LocalDateTime.class);
        });
    }
}
