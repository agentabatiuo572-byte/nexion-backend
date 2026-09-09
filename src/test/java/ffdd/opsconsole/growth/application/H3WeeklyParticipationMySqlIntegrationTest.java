package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.growth.mapper.H3WeeklyParticipationMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in real-MySQL coverage for the H3 weekly evaluator.
 *
 * <p>The test creates only an owned random fixture schema and never inserts, updates, or deletes
 * rows in the configured database. It is deliberately opt-in because it executes fixture DDL only
 * when {@code NEXION_TEST_DB_PASSWORD} is explicitly supplied by the test runner.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class H3WeeklyParticipationMySqlIntegrationTest {
    private static final long USER_ID = 980_701L;
    private static final long ROLLBACK_USER_ID = 980_702L;
    private static final Pattern OWNED_DATABASE = Pattern.compile("nx_h3_weekly_test_[0-9a-f]{32}");
    private static final Pattern LOOPBACK_SERVER_URL = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    private Connection adminConnection;
    private JdbcTemplate fixtureJdbc;
    private JdbcTemplate transactionJdbc;
    private DriverManagerDataSource fixtureDataSource;
    private TransactionTemplate repeatableRead;
    private H3WeeklyParticipationMapper participationMapper;
    private CanonicalStateMapper canonicalMapper;
    private String fixtureDatabase;
    private String fixtureUrl;
    private boolean fixtureCreated;

    @BeforeEach
    void createOwnedFixtureSchema() throws Exception {
        String url = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        requireServerUrlWithoutCatalog(url);
        String user = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        adminConnection = DriverManager.getConnection(url, user, password);
        assertThat(currentDatabase(adminConnection)).isNull();
        fixtureDatabase = "nx_h3_weekly_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(OWNED_DATABASE.matcher(fixtureDatabase).matches()).isTrue();
        fixtureUrl = fixtureUrl(url, fixtureDatabase);

        try {
            try (var statement = adminConnection.createStatement()) {
                statement.execute("CREATE DATABASE `" + fixtureDatabase
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            fixtureCreated = true;
            adminConnection.setCatalog(fixtureDatabase);
            fixtureJdbc = new JdbcTemplate(new SingleConnectionDataSource(adminConnection, true));
            assertThat(fixtureJdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureDatabase);
            createFixtureTables();
            ScriptUtils.executeSqlScript(adminConnection,
                    new FileSystemResource("scripts/migrations/20260907_h3_weekly_participation.sql"));
        } catch (Exception failure) {
            discardFixture();
            throw failure;
        }

        fixtureDataSource = new DriverManagerDataSource(fixtureUrl, user, password);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixtureDataSource);
        repeatableRead = new TransactionTemplate(manager);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatableRead.setTimeout(10);
        transactionJdbc = new JdbcTemplate(fixtureDataSource);

        Configuration configuration = new Configuration(new Environment("h3-weekly-mysql",
                new SpringManagedTransactionFactory(), fixtureDataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(H3WeeklyParticipationMapper.class);
        configuration.addMapper(CanonicalStateMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(
                new MybatisSqlSessionFactoryBuilder().build(configuration));
        participationMapper = session.getMapper(H3WeeklyParticipationMapper.class);
        canonicalMapper = session.getMapper(CanonicalStateMapper.class);
    }

    @AfterEach
    void discardOwnedFixtureSchema() throws Exception {
        try {
            discardFixture();
        } finally {
            if (adminConnection != null) adminConnection.close();
        }
    }

    @Test
    void mapsVisibleZeroStockProductAndStrictProductionCompletedReceipt() {
        seedUser(USER_ID);
        fixtureJdbc.update("INSERT INTO nx_product (id,product_no,status,store_visible,stock,is_deleted)"
                + " VALUES (1,'ZERO-STOCK','ACTIVE',1,0,0)");
        fixtureJdbc.update("INSERT INTO nx_compute_task (task_no,user_id,source_environment,status,completed_at,proof_consumed_at,is_deleted)"
                + " VALUES ('TASK-GOOD',?,'PRODUCTION','COMPLETED','2026-09-07 08:30:00','2026-09-07 08:30:00',0)", USER_ID);
        fixtureJdbc.update("INSERT INTO nx_compute_receipt (task_no,user_id,source_environment,earning_status,is_deleted)"
                + " VALUES ('TASK-GOOD',?,'PRODUCTION','CREDITED',0)", USER_ID);
        fixtureJdbc.update("INSERT INTO nx_compute_task (task_no,user_id,source_environment,status,completed_at,proof_consumed_at,is_deleted)"
                + " VALUES ('TASK-UNPAID',?,'PRODUCTION','COMPLETED','2026-09-07 08:30:00','2026-09-07 08:30:00',0)", USER_ID);
        fixtureJdbc.update("INSERT INTO nx_compute_receipt (task_no,user_id,source_environment,earning_status,is_deleted)"
                + " VALUES ('TASK-UNPAID',?,'PRODUCTION','PENDING',0)", USER_ID);
        fixtureJdbc.update("INSERT INTO nx_compute_task (task_no,user_id,source_environment,status,completed_at,proof_consumed_at,is_deleted)"
                + " VALUES ('TASK-NOT-COMPLETED',?,'PRODUCTION','RUNNING','2026-09-07 08:30:00','2026-09-07 08:30:00',0)", USER_ID);
        fixtureJdbc.update("INSERT INTO nx_compute_receipt (task_no,user_id,source_environment,earning_status,is_deleted)"
                + " VALUES ('TASK-NOT-COMPLETED',?,'PRODUCTION','CREDITED',0)", USER_ID);
        fixtureJdbc.update("INSERT INTO nx_compute_task (task_no,user_id,source_environment,status,completed_at,proof_consumed_at,is_deleted)"
                + " VALUES ('TASK-SANDBOX-RECEIPT',?,'PRODUCTION','COMPLETED','2026-09-07 08:30:00','2026-09-07 08:30:00',0)", USER_ID);
        fixtureJdbc.update("INSERT INTO nx_compute_receipt (task_no,user_id,source_environment,earning_status,is_deleted)"
                + " VALUES ('TASK-SANDBOX-RECEIPT',?,'SANDBOX','CREDITED',0)", USER_ID);

        assertThat(canonicalMapper.findVisibleStorefrontProduct("ZERO-STOCK")).isEqualTo(1L);
        assertThat(participationMapper.verifiedProductionCompletedTask(USER_ID, "TASK-GOOD").completedAt())
                .isEqualTo(LocalDateTime.of(2026, 9, 7, 8, 30));
        assertThat(participationMapper.verifiedProductionCompletedTask(USER_ID, "TASK-UNPAID")).isNull();
        assertThat(participationMapper.verifiedProductionCompletedTask(USER_ID, "TASK-NOT-COMPLETED")).isNull();
        assertThat(participationMapper.verifiedProductionCompletedTask(USER_ID, "TASK-SANDBOX-RECEIPT")).isNull();
    }

    @Test
    void twoRepeatableReadTransactionsAtFortyNineAndFiftyEmitExactlyOneThresholdFact() throws Exception {
        seedUser(USER_ID);
        for (int index = 1; index <= 48; index++) {
            fixtureJdbc.update("INSERT INTO nx_growth_weekly_participation_observation"
                            + " (user_id,instance_key,observation_type,subject_key,observed_at,created_at,updated_at,is_deleted)"
                            + " VALUES (?, 'WEEK:2026-W37', 'VERIFIED_PRODUCTION_COMPUTE_COMPLETION', ?, NOW(), NOW(), NOW(), 0)",
                    USER_ID, "TASK-" + index);
        }
        EventOutboxService outbox = mock(EventOutboxService.class);
        H3WeeklyParticipationEvaluator evaluator = new H3WeeklyParticipationEvaluator(participationMapper, outbox, CLOCK);
        CountDownLatch snapshotsEstablished = new CountDownLatch(2);
        CountDownLatch releaseEvaluators = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(concurrentRecord(snapshotsEstablished, releaseEvaluators, evaluator, "TASK-49")),
                    executor.submit(concurrentRecord(snapshotsEstablished, releaseEvaluators, evaluator, "TASK-50")));
            assertThat(snapshotsEstablished.await(5, TimeUnit.SECONDS)).isTrue();
            releaseEvaluators.countDown();
            assertThat(results.get(0).get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(results.get(1).get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(inRepeatableRead(() -> transactionJdbc.queryForObject("SELECT @@transaction_isolation", String.class)))
                .isEqualToIgnoringCase("REPEATABLE-READ");
        assertThat(fixtureJdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_weekly_participation_observation"
                + " WHERE user_id=? AND instance_key='WEEK:2026-W37' AND observation_type='VERIFIED_PRODUCTION_COMPUTE_COMPLETION'",
                Integer.class, USER_ID)).isEqualTo(50);
        assertThat(fixtureJdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_weekly_participation_threshold"
                + " WHERE user_id=? AND instance_key='WEEK:2026-W37' AND threshold_event_type='H3_COMPUTE_COMPLETED_50'"
                + " AND emitted_at IS NOT NULL", Integer.class, USER_ID)).isEqualTo(1);
        verify(outbox, times(1)).publishUserEventAt(anyString(), anyString(), eq("H3_COMPUTE_COMPLETED_50"),
                eq(USER_ID), anyString(), anyInt(), anyString(),
                eq(LocalDateTime.of(2026, 9, 7, 8, 0)), any());
    }

    @Test
    void transactionRollsBackObservationAndMarkerWhenThresholdOutboxWriteFails() {
        seedUser(ROLLBACK_USER_ID);
        for (int index = 1; index <= 2; index++) {
            fixtureJdbc.update("INSERT INTO nx_growth_weekly_participation_observation"
                            + " (user_id,instance_key,observation_type,subject_key,observed_at,created_at,updated_at,is_deleted)"
                            + " VALUES (?, 'WEEK:2026-W37', 'STOREFRONT_PRODUCT_DETAIL', ?, NOW(), NOW(), NOW(), 0)",
                    ROLLBACK_USER_ID, "PRODUCT-" + index);
        }
        EventOutboxService outbox = mock(EventOutboxService.class);
        doThrow(new IllegalStateException("fixture-outbox-failure")).when(outbox).publishUserEventAt(
                anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyString(),
                any(LocalDateTime.class), any());
        H3WeeklyParticipationEvaluator evaluator = new H3WeeklyParticipationEvaluator(participationMapper, outbox, CLOCK);

        assertThatThrownBy(() -> inRepeatableRead(() -> evaluator.recordStorefrontProductDetail(ROLLBACK_USER_ID, "PRODUCT-3")))
                .hasMessage("fixture-outbox-failure");

        assertThat(fixtureJdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_weekly_participation_observation"
                + " WHERE user_id=? AND instance_key='WEEK:2026-W37' AND observation_type='STOREFRONT_PRODUCT_DETAIL'",
                Integer.class, ROLLBACK_USER_ID)).isEqualTo(2);
        assertThat(fixtureJdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_weekly_participation_threshold"
                + " WHERE user_id=? AND instance_key='WEEK:2026-W37' AND threshold_event_type='H3_STOREFRONT_THREE_PRODUCTS_VIEWED'",
                Integer.class, ROLLBACK_USER_ID)).isZero();
    }

    private Callable<Boolean> concurrentRecord(
            CountDownLatch snapshotsEstablished, CountDownLatch releaseEvaluators,
            H3WeeklyParticipationEvaluator evaluator, String taskNo) {
        return () -> inRepeatableRead(() -> {
            Integer snapshotCount = transactionJdbc.queryForObject(
                    "SELECT COUNT(*) FROM nx_growth_weekly_participation_observation"
                            + " WHERE user_id=? AND instance_key='WEEK:2026-W37'"
                            + " AND observation_type='VERIFIED_PRODUCTION_COMPUTE_COMPLETION'",
                    Integer.class, USER_ID);
            if (!Integer.valueOf(48).equals(snapshotCount)) {
                throw new IllegalStateException("fixture-snapshot-count-invalid:" + snapshotCount);
            }
            snapshotsEstablished.countDown();
            if (!releaseEvaluators.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fixture-evaluator-release-timeout");
            }
            return evaluator.recordVerifiedProductionComputeCompletion(
                    USER_ID, taskNo, LocalDateTime.of(2026, 9, 7, 8, 0));
        });
    }

    private <T> T inRepeatableRead(Callable<T> work) {
        return repeatableRead.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private void createFixtureTables() {
        fixtureJdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY, status VARCHAR(24) NOT NULL,"
                + " is_deleted TINYINT NOT NULL DEFAULT 0, sandbox TINYINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL)");
        fixtureJdbc.execute("CREATE TABLE nx_product (id BIGINT PRIMARY KEY, product_no VARCHAR(96) NOT NULL,"
                + " status VARCHAR(24) NOT NULL, store_visible TINYINT NULL, stock INT NULL, is_deleted TINYINT NOT NULL DEFAULT 0)");
        fixtureJdbc.execute("CREATE TABLE nx_compute_task (task_no VARCHAR(96) NOT NULL, user_id BIGINT NOT NULL,"
                + " source_environment VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL, completed_at DATETIME NULL,"
                + " proof_consumed_at DATETIME NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (task_no,user_id))");
        fixtureJdbc.execute("CREATE TABLE nx_compute_receipt (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_no VARCHAR(96) NOT NULL,"
                + " user_id BIGINT NOT NULL, source_environment VARCHAR(24) NOT NULL, earning_status VARCHAR(24) NOT NULL,"
                + " is_deleted TINYINT NOT NULL DEFAULT 0)");
        fixtureJdbc.execute("CREATE TABLE nx_config_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, config_key VARCHAR(128) NOT NULL,"
                + " config_value VARCHAR(255) NULL, status TINYINT NOT NULL DEFAULT 1, is_deleted TINYINT NOT NULL DEFAULT 0)");
    }

    private void seedUser(long userId) {
        fixtureJdbc.update("INSERT INTO nx_user (id,status,is_deleted,sandbox,created_at)"
                + " VALUES (?,'ACTIVE',0,0,'2026-01-01 00:00:00')", userId);
    }

    private void discardFixture() throws Exception {
        if (fixtureCreated && fixtureDatabase != null && OWNED_DATABASE.matcher(fixtureDatabase).matches()) {
            try (var statement = adminConnection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + fixtureDatabase + "`");
            } finally {
                fixtureCreated = false;
            }
        }
    }

    private static String fixtureUrl(String sourceUrl, String database) {
        int query = sourceUrl.indexOf('?');
        String base = query >= 0 ? sourceUrl.substring(0, query) : sourceUrl;
        String suffix = query >= 0 ? sourceUrl.substring(query) : "";
        int slash = base.lastIndexOf('/');
        if (slash < "jdbc:mysql://".length()) throw new IllegalArgumentException("NEXION_TEST_DB_URL_DATABASE_REQUIRED");
        return base.substring(0, slash + 1) + database + suffix;
    }

    static void requireServerUrlWithoutCatalog(String sourceUrl) {
        if (sourceUrl == null || !LOOPBACK_SERVER_URL.matcher(sourceUrl.trim()).matches()) {
            throw new IllegalArgumentException("NEXION_TEST_DB_SERVER_URL_MUST_TARGET_LOOPBACK_WITHOUT_CATALOG");
        }
    }

    private static String currentDatabase(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next()) throw new IllegalStateException("H3_FIXTURE_DATABASE_SCOPE_UNAVAILABLE");
            return result.getString(1);
        }
    }
}
