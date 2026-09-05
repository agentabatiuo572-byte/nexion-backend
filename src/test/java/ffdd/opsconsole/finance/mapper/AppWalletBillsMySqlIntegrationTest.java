package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.finance.application.AppWalletBillsService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Isolated real-MySQL contract for keyset paging and server-side wallet aggregates. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppWalletBillsMySqlIntegrationTest {
    private static final long USER = 701L;
    private static final long OTHER_USER = 702L;
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 8, 31, 0, 0);
    private static final String INDEX_MIGRATION = "scripts/migrations/20260831_app_wallet_bills_cursor_index.sql";

    private Connection connection;
    private JdbcTemplate jdbc;
    private String fixtureDatabase;
    private boolean fixtureDatabaseCreated;
    private AppWalletBillsMapper mapper;

    @BeforeAll
    void createOwnedFixtureDatabase() throws Exception {
        connection = DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        fixtureDatabase = "nx_wallet_bills_test_" + UUID.randomUUID().toString().replace("-", "");
        assertOwnedFixtureDatabase();
        try {
            jdbc.execute("CREATE DATABASE `" + fixtureDatabase + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            fixtureDatabaseCreated = true;
            jdbc.execute("USE `" + fixtureDatabase + "`");
            jdbc.execute("""
                    CREATE TABLE nx_user (
                      id BIGINT PRIMARY KEY, sandbox TINYINT NOT NULL, status VARCHAR(32) NOT NULL,
                      is_deleted TINYINT NOT NULL DEFAULT 0
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE nx_wallet_ledger (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, biz_no VARCHAR(96) NOT NULL,
                      biz_type VARCHAR(64), asset VARCHAR(16), direction VARCHAR(16), amount DECIMAL(18,6),
                      balance_after DECIMAL(18,6), status VARCHAR(32), remark VARCHAR(255), created_at DATETIME(6) NOT NULL,
                      is_deleted TINYINT NOT NULL DEFAULT 0,
                      UNIQUE KEY uk_wallet_ledger_biz (biz_no,asset,direction),
                      KEY idx_wallet_ledger_user_time (user_id,created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            executeIndexMigration();
            executeIndexMigration();
            Configuration configuration = new Configuration(new Environment("app-wallet-bills-test",
                    new SpringManagedTransactionFactory(), new SingleConnectionDataSource(connection, true)));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(AppWalletBillsMapper.class);
            mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                    .getMapper(AppWalletBillsMapper.class);
        } catch (Exception failure) {
            dropOwnedFixtureDatabase();
            throw failure;
        }
    }

    @BeforeEach
    void clearOnlyTheOwnedFixtureDatabase() {
        assertOwnedFixtureDatabase();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureDatabase);
        jdbc.execute("TRUNCATE TABLE nx_wallet_ledger");
        jdbc.execute("TRUNCATE TABLE nx_user");
        user(USER, 0, "ACTIVE", 0);
        user(OTHER_USER, 0, "ACTIVE", 0);
    }

    @AfterAll
    void discardOwnedFixtureDatabase() throws Exception {
        if (connection == null) return;
        try {
            dropOwnedFixtureDatabase();
        } finally {
            connection.close();
        }
    }

    @Test
    void keysetFiltersAndSummaryStayScopedAndExactAcrossMoreThanOneThousandRows() {
        for (int index = 0; index < 1_100; index++) {
            ledger(USER, "BULK-" + index, "EARN", "NEX", "IN", "1", "100", "SUCCESS",
                    DAY.plusHours(12).minusSeconds(index), 0);
        }
        LocalDateTime tie = DAY.plusHours(13).plusNanos(123_456_000);
        ledger(USER, "TIE-A", "EARN", "NEX", "IN", "1", "101", "SUCCESS", tie, 0);
        ledger(USER, "TIE-B", "EARN", "NEX", "IN", "1", "102", "SUCCESS", tie, 0);
        ledger(OTHER_USER, "OTHER", "EARN", "NEX", "IN", "999", "999", "SUCCESS", DAY.plusHours(14), 0);
        ledger(USER, "DELETED", "EARN", "NEX", "IN", "999", "999", "SUCCESS", DAY.plusHours(14), 1);
        ledger(USER, "ZERO", "EARN", "NEX", "IN", "0", "0", "SUCCESS", DAY.plusHours(10), 0);
        ledger(USER, "NEGATIVE", "EARN", "NEX", "IN", "-1", "0", "SUCCESS", DAY.plusHours(10), 0);
        ledger(USER, "NULL-AMOUNT", "EARN", "NEX", "IN", null, "0", "SUCCESS", DAY.plusHours(10), 0);
        ledger(USER, "STAKE-ACH", "STAKE_ACHIEVEMENT", "NEX", "IN", "7", "107", "SUCCESS", DAY.plusHours(9), 0);
        ledger(USER, "ORDER-QUEST", "ORDER_QUEST", "NEX", "IN", "8", "108", "SUCCESS", DAY.plusHours(9), 0);
        ledger(USER, "QUEST", "QUEST_REWARD", "NEX", "IN", "3", "111", "POSTED", DAY.plusHours(8), 0);
        ledger(USER, "COMMISSION", "TEAM_COMMISSION", "USDT", "IN", "2", "2", "PENDING", DAY.plusHours(7), 0);
        ledger(USER, "PENDING", "EARN", "NEX", "IN", "4", "112", "PENDING", DAY.plusHours(6), 0);
        ledger(USER, "FAILED", "EARN", "NEX", "IN", "5", "117", "FAILED", DAY.plusHours(5), 0);
        ledger(USER, "NEXT-MONTH", "EARN", "NEX", "IN", "13", "130", "SUCCESS", DAY.plusDays(1), 0);

        List<AppWalletBillsMapper.LedgerRow> first = mapper.rowsAfter(USER, 51, null, null, null, null, null);
        AppWalletBillsMapper.LedgerRow boundary = first.get(49);
        ledger(USER, "CONCURRENT-NEW", "EARN", "NEX", "IN", "1", "131", "SUCCESS", DAY.plusDays(2), 0);
        List<AppWalletBillsMapper.LedgerRow> second = mapper.rowsAfter(USER, 51, null, null, null,
                boundary.createdAt(), boundary.id());

        // The fifty-first row is a look-ahead probe, not part of the first response page.
        Set<Long> ids = new HashSet<>();
        first.subList(0, 50).forEach(row -> ids.add(row.id()));
        assertThat(second).allSatisfy(row -> assertThat(ids).doesNotContain(row.id()));
        assertThat(second).noneSatisfy(row -> assertThat(row.bizNo()).isEqualTo("CONCURRENT-NEW"));
        assertThat(first).extracting(AppWalletBillsMapper.LedgerRow::bizNo).doesNotContain("OTHER", "DELETED");
        assertThat(mapper.rowsAfter(USER, 2_000, null, "IN", null, null, null))
                .noneSatisfy(row -> assertThat(row.bizNo()).isIn("ZERO", "NEGATIVE", "NULL-AMOUNT"));
        assertThat(mapper.rowsAfter(USER, 20, null, null, "REWARD", null, null))
                .extracting(AppWalletBillsMapper.LedgerRow::bizNo).contains("QUEST", "COMMISSION")
                .doesNotContain("STAKE-ACH", "ORDER-QUEST");

        AppWalletBillsMapper.SummaryRow summary = mapper.summary(USER, DAY, DAY.plusDays(1), DAY.withDayOfMonth(1),
                DAY.withDayOfMonth(1).plusMonths(1));
        assertThat(summary.rewardsUsdt()).isEqualByComparingTo("2");
        assertThat(summary.rewardsNex()).isEqualByComparingTo("3");
        assertThat(summary.todayNexEarn()).isEqualByComparingTo("1114");
        assertThat(summary.pendingNex()).isEqualByComparingTo("4");
        assertThat(summary.monthBillCount()).isEqualTo(1_111L);
        assertThat(mapper.recentNexRows(USER, 10)).noneSatisfy(row -> assertThat(row.bizNo()).isEqualTo("FAILED"));
        assertThat(jdbc.queryForList("""
                SELECT column_name,collation FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger'
                   AND index_name='idx_wallet_ledger_user_deleted_cursor'
                 ORDER BY seq_in_index
                """)).extracting(row -> row.get("COLUMN_NAME") + ":" + row.get("COLLATION"))
                .containsExactly("user_id:A", "is_deleted:A", "created_at:D", "id:D");
    }

    @Test
    void summaryAndRecentRowsShareTheInitialRepeatableReadSnapshotDespiteAConcurrentWriter() throws Exception {
        DataSource dataSource = fixtureDataSource();
        AppWalletBillsMapper delegate = new SqlSessionTemplate(sessionFactory(dataSource)).getMapper(AppWalletBillsMapper.class);
        JdbcTemplate concurrentWriter = new JdbcTemplate(dataSource);
        ExecutorService writerPool = Executors.newSingleThreadExecutor();
        try {
            AppWalletBillsMapper mapperThatWritesBetweenReads = (AppWalletBillsMapper) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {AppWalletBillsMapper.class}, (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(delegate, args);
                            if ("summary".equals(method.getName())) {
                                writerPool.submit(() -> concurrentWriter.update("""
                                        INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,is_deleted)
                                        VALUES(701,'LATE-SNAPSHOT-REWARD','QUEST_REWARD','NEX','IN',500,500,'SUCCESS','concurrent',UTC_TIMESTAMP(6),0)
                                        """)).get(10, TimeUnit.SECONDS);
                            }
                            return value;
                        } catch (InvocationTargetException exception) {
                            throw exception.getTargetException();
                        }
                    });
            AppWalletBillsService service = transactionalService(mapperThatWritesBetweenReads, dataSource);

            Map<String, Object> response = service.summary(USER).getData();
            LocalDateTime lateCreatedAt = concurrentWriter.queryForObject(
                    "SELECT created_at FROM nx_wallet_ledger WHERE biz_no='LATE-SNAPSHOT-REWARD'", LocalDateTime.class);

            assertThat((BigDecimal) response.get("rewardsNex")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(response).containsEntry("latestRewardAt", null);
            assertThat((List<?>) response.get("recentNexBills")).isEmpty();
            assertThat(Instant.parse(String.valueOf(response.get("asOf"))))
                    .isBefore(lateCreatedAt.toInstant(java.time.ZoneOffset.UTC));
        } finally {
            writerPool.shutdownNow();
        }
    }

    private void user(long id, int sandbox, String status, int deleted) {
        jdbc.update("INSERT INTO nx_user(id,sandbox,status,is_deleted) VALUES(?,?,?,?)", id, sandbox, status, deleted);
    }

    private void ledger(long userId, String bizNo, String bizType, String asset, String direction, String amount,
                        String balanceAfter, String status, LocalDateTime createdAt, int deleted) {
        jdbc.update("""
                INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,is_deleted)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, userId, bizNo, bizType, asset, direction,
                amount == null ? null : new BigDecimal(amount), new BigDecimal(balanceAfter), status, "fixture", createdAt, deleted);
    }

    private void assertOwnedFixtureDatabase() {
        assertThat(fixtureDatabase).matches("nx_wallet_bills_test_[0-9a-f]{32}");
    }

    private void executeIndexMigration() throws Exception {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(INDEX_MIGRATION));
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        Configuration configuration = new Configuration(new Environment("app-wallet-bills-transaction-test",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppWalletBillsMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private AppWalletBillsService transactionalService(AppWalletBillsMapper mapper, DataSource dataSource) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AppWalletBillsService target = new AppWalletBillsService(mapper, environment);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (AppWalletBillsService) proxyFactory.getProxy();
    }

    private DataSource fixtureDataSource() {
        return new DriverManagerDataSource(fixtureJdbcUrl(), System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
    }

    private String fixtureJdbcUrl() {
        try {
            String url = connection.getMetaData().getURL();
            int query = url.indexOf('?');
            String base = query < 0 ? url : url.substring(0, query);
            String parameters = query < 0 ? "" : url.substring(query);
            return base.substring(0, base.lastIndexOf('/') + 1) + fixtureDatabase + parameters;
        } catch (Exception exception) {
            throw new IllegalStateException("fixture JDBC URL unavailable", exception);
        }
    }

    private void dropOwnedFixtureDatabase() {
        if (!fixtureDatabaseCreated) return;
        assertOwnedFixtureDatabase();
        jdbc.execute("USE information_schema");
        jdbc.execute("DROP DATABASE `" + fixtureDatabase + "`");
        fixtureDatabaseCreated = false;
    }
}
