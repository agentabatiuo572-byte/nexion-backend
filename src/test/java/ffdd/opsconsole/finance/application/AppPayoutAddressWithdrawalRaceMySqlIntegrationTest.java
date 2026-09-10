package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import ffdd.opsconsole.finance.application.AppPayoutAddressService.SaveRequest;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Deliberately makes the service's default transaction RR.  It stays red for
 * the old code and for a user lock without READ_COMMITTED on save(): the
 * ordinary active-user read has already established the old snapshot.  The
 * idempotency mock invokes the action inline, matching production's REQUIRED
 * action join but intentionally not exercising durable claim persistence.
 */
class AppPayoutAddressWithdrawalRaceMySqlIntegrationTest {
    private static final String PREFIX = "nexion_payout_race_it_";
    private static final long USER_ID = 7L;
    private static final String NETWORK = "USDT-BEP20";
    private static final String OLD_ADDRESS = "0x" + "ab".repeat(20);
    private static final String NEW_ADDRESS = "0x" + "cd".repeat(20);

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(schema.length()).isLessThanOrEqualTo(64);
        assertThat(jdbcUrl("127.0.0.1:13306", schema)).contains(":13306/" + schema + "?");
        for (String endpoint : Arrays.asList(null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/other")) {
            assertThatThrownBy(() -> jdbcUrl(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : Arrays.asList(null, "nexion", PREFIX + "a", schema + "`")) {
            assertThatThrownBy(() -> jdbcUrl("127.0.0.1:13306", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_PAYOUT_ADDRESS_RACE_IT", matches = "true")
    void committedWithdrawalAfterInitialReadBlocksPayoutAddressChange() throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            assertPort(admin);
            execute(admin, "CREATE DATABASE " + schema);
            try {
                runSnapshotRace(schema);
            } finally {
                execute(admin, "DROP DATABASE " + schema);
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_PAYOUT_ADDRESS_RACE_IT", matches = "true")
    void userMutexPreventsCountBeforeCompetingWithdrawalCommit() throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            assertPort(admin);
            execute(admin, "CREATE DATABASE " + schema);
            try {
                runMutexRace(schema);
            } finally {
                execute(admin, "DROP DATABASE " + schema);
            }
        }
    }

    private void runSnapshotRace(String schema) throws Exception {
        DataSource dataSource = new RepeatableReadDataSource(jdbcUrl(endpoint(), schema), "root", password());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertSelectedSchema(dataSource, schema);
        createFixture(jdbc);
        CountDownLatch activeUserRead = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AppPayoutAddressMapper realMapper = mapper(dataSource);
            AppPayoutAddressMapper mapper = mock(AppPayoutAddressMapper.class,
                    withSettings().defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(realMapper)));
            doAnswer(invocation -> {
                Long active = realMapper.activeUser(invocation.getArgument(0));
                activeUserRead.countDown();
                if (!releaseSave.await(5, TimeUnit.SECONDS)) throw new AssertionError("save did not wait for withdrawal");
                return active;
            }).when(mapper).activeUser(USER_ID);

            AppPayoutAddressService service = transactional(service(mapper), dataSource);
            Future<Throwable> save = executor.submit(() -> {
                try {
                    service.save(USER_ID, new SaveRequest(NETWORK, NEW_ADDRESS, "PAYOUT-RACE", "123456"), "race-key");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(activeUserRead.await(5, TimeUnit.SECONDS)).isTrue();

            // Match the production submit mutex order: user, then payout address,
            // then commit the durable in-flight withdrawal before save() continues.
            try (Connection withdrawal = dataSource.getConnection()) {
                withdrawal.setAutoCommit(false);
                execute(withdrawal, "SELECT id FROM nx_user WHERE id=7 FOR UPDATE");
                execute(withdrawal, "SELECT user_id FROM nx_user_payout_address"
                        + " WHERE user_id=7 AND network='USDT-BEP20' FOR UPDATE");
                execute(withdrawal, "INSERT INTO nx_withdrawal_order VALUES(1,7,'SUBMITTED',0)");
                withdrawal.commit();
            }
            releaseSave.countDown();
            Throwable failure = save.get(10, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(BizException.class);
            assertThat(failure.getMessage()).isEqualTo("PAYOUT_ADDRESS_CHANGE_BLOCKED_BY_WITHDRAWAL");
            assertThat(text(jdbc, "SELECT address FROM nx_user_payout_address WHERE user_id=7 AND network='USDT-BEP20'"))
                    .isEqualTo(OLD_ADDRESS);
            assertThat(number(jdbc, "SELECT COUNT(*) FROM nx_user_payout_address_history")).isZero();
        } finally {
            releaseSave.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void runMutexRace(String schema) throws Exception {
        DataSource dataSource = new RepeatableReadDataSource(jdbcUrl(endpoint(), schema), "root", password());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertSelectedSchema(dataSource, schema);
        createFixture(jdbc);
        CountDownLatch activeUserRead = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch countCalled = new CountDownLatch(1);
        CountDownLatch userLockCalled = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AppPayoutAddressMapper realMapper = mapper(dataSource);
            AppPayoutAddressMapper mapper = mock(AppPayoutAddressMapper.class,
                    withSettings().defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(realMapper)));
            doAnswer(invocation -> {
                Long active = realMapper.activeUser(invocation.getArgument(0));
                activeUserRead.countDown();
                if (!releaseSave.await(5, TimeUnit.SECONDS)) throw new AssertionError("save did not wait for withdrawal");
                return active;
            }).when(mapper).activeUser(USER_ID);
            doAnswer(invocation -> {
                int count = realMapper.unsettledWithdrawalCount(invocation.getArgument(0));
                countCalled.countDown();
                return count;
            }).when(mapper).unsettledWithdrawalCount(USER_ID);
            doAnswer(invocation -> {
                userLockCalled.countDown();
                return realMapper.lockActiveUser(invocation.getArgument(0));
            }).when(mapper).lockActiveUser(USER_ID);

            AppPayoutAddressService service = transactional(service(mapper), dataSource);
            Future<Throwable> save = executor.submit(() -> {
                try {
                    service.save(USER_ID, new SaveRequest(NETWORK, NEW_ADDRESS, "PAYOUT-RACE", "123456"), "race-key");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(activeUserRead.await(5, TimeUnit.SECONDS)).isTrue();

            try (Connection withdrawal = dataSource.getConnection()) {
                withdrawal.setAutoCommit(false);
                execute(withdrawal, "SELECT id FROM nx_user WHERE id=7 FOR UPDATE");
                execute(withdrawal, "SELECT user_id FROM nx_user_payout_address"
                        + " WHERE user_id=7 AND network='USDT-BEP20' FOR UPDATE");
                releaseSave.countDown();

                // A fixed service reaches the user-lock spy and cannot count while this
                // connection owns the user row.  An old/RC-only service reaches count
                // first and then waits only on the address row.
                boolean attemptsUserLock = userLockCalled.await(1, TimeUnit.SECONDS);
                if (attemptsUserLock) {
                    assertThat(countCalled.await(250, TimeUnit.MILLISECONDS)).isFalse();
                } else {
                    assertThat(countCalled.await(5, TimeUnit.SECONDS)).isTrue();
                }
                execute(withdrawal, "INSERT INTO nx_withdrawal_order VALUES(1,7,'SUBMITTED',0)");
                withdrawal.commit();
            }
            Throwable failure = save.get(10, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(BizException.class);
            assertThat(failure.getMessage()).isEqualTo("PAYOUT_ADDRESS_CHANGE_BLOCKED_BY_WITHDRAWAL");
            assertThat(text(jdbc, "SELECT address FROM nx_user_payout_address WHERE user_id=7 AND network='USDT-BEP20'"))
                    .isEqualTo(OLD_ADDRESS);
            assertThat(number(jdbc, "SELECT COUNT(*) FROM nx_user_payout_address_history")).isZero();
        } finally {
            releaseSave.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AppPayoutAddressService service(AppPayoutAddressMapper mapper) {
        PayoutAddressOtpAttemptService otp = mock(PayoutAddressOtpAttemptService.class);
        org.mockito.Mockito.when(otp.verifyAndConsume(USER_ID, "PAYOUT-RACE", "123456")).thenReturn(true);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get()).when(idempotency)
                .execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
        return new AppPayoutAddressService(mapper, mock(ffdd.opsconsole.auth.application.UserOtpDeliveryService.class),
                mock(AuditLogService.class), idempotency, otp, null, null);
    }

    @SuppressWarnings("unchecked")
    private AppPayoutAddressService transactional(AppPayoutAddressService target, DataSource dataSource) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (AppPayoutAddressService) proxy.getProxy();
    }

    private AppPayoutAddressMapper mapper(DataSource dataSource) {
        Configuration configuration = new Configuration(new Environment("payout-address-race",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(AppPayoutAddressMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory).getMapper(AppPayoutAddressMapper.class);
    }

    private void createFixture(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,status VARCHAR(16),is_deleted INT,sandbox INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_withdrawal_order (id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(32),is_deleted INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_user_payout_address (user_id BIGINT,network VARCHAR(32),address VARCHAR(128),"
                + "status VARCHAR(16),effective_at DATETIME,next_change_allowed_at DATETIME,version BIGINT,"
                + "created_at DATETIME,updated_at DATETIME,is_deleted INT,PRIMARY KEY(user_id,network)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_user_payout_address_history (user_id BIGINT,network VARCHAR(32),"
                + "previous_address VARCHAR(128),new_address VARCHAR(128),change_type VARCHAR(16),created_at DATETIME) ENGINE=InnoDB");
        jdbc.update("INSERT INTO nx_user VALUES(7,'ACTIVE',0,0)");
        jdbc.update("INSERT INTO nx_user_payout_address VALUES(7,?,?,'ACTIVE',DATE_SUB(NOW(),INTERVAL 2 DAY),"
                + "DATE_SUB(NOW(),INTERVAL 1 DAY),0,NOW(),NOW(),0)", NETWORK, OLD_ADDRESS);
    }

    private static String jdbcUrl(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}"))) {
            throw new IllegalArgumentException("owned UUID schema required");
        }
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    }

    private static String endpoint() { return System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"); }
    private static String password() { return System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""); }
    private static Connection connect(String schema) throws SQLException { return DriverManager.getConnection(jdbcUrl(endpoint(), schema), "root", password()); }
    private static void execute(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private static void assertPort(Connection connection) throws SQLException { try (var statement = connection.createStatement(); var port = statement.executeQuery("SELECT @@port")) { assertThat(port.next()).isTrue(); assertThat(port.getInt(1)).isEqualTo(13306); } }
    private static void assertSelectedSchema(DataSource dataSource, String schema) throws SQLException { try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement(); var selected = statement.executeQuery("SELECT DATABASE()")) { assertThat(selected.next()).isTrue(); assertThat(selected.getString(1)).isEqualTo(schema); } }
    private static String text(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, String.class); }
    private static long number(JdbcTemplate jdbc, String sql) { Long value = jdbc.queryForObject(sql, Long.class); return value == null ? 0L : value; }

    private static final class RepeatableReadDataSource extends DriverManagerDataSource {
        private RepeatableReadDataSource(String url, String username, String password) { super(url, username, password); }
        @Override public Connection getConnection() throws SQLException { return repeatable(super.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return repeatable(super.getConnection(username, password)); }
        private Connection repeatable(Connection connection) throws SQLException { connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); return connection; }
    }
}
