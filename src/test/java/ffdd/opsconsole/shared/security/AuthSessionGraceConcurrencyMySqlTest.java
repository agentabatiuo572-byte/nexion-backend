package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.outbox.CanonicalEventSchemaMySqlFixture;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "NEXION_SESSION_GRACE_IT", matches = "true")
class AuthSessionGraceConcurrencyMySqlTest {
    @Test
    void concurrentOldBearersAndLiveTouchDoNotDeadlock() throws Exception {
        try (var fixture = new CanonicalEventSchemaMySqlFixture("nx_user_session")) {
            assertThat(fixture.jdbc().queryForObject("SELECT @@port", Integer.class)).isEqualTo(33329);
            assertThat(fixture.jdbc().queryForList("SHOW INDEX FROM nx_user_session").stream()
                    .map(row -> row.get("Key_name")).distinct()).contains(
                            "PRIMARY", "uk_user_session_token", "idx_user_session_chain");
            seed(fixture);
            try (var context = context(fixture)) {
            AuthSessionMapper mapper = context.getBean(AuthSessionMapper.class);
            var pool = Executors.newFixedThreadPool(9);
            var barrier = new CyclicBarrier(9);
            AtomicInteger deadlocks = new AtomicInteger();
            AtomicInteger accepted = new AtomicInteger();
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            try {
                for (int worker = 0; worker < 9; worker++) {
                    int kind = worker % 3;
                    futures.add(pool.submit(() -> {
                        for (int round = 0; round < 15; round++) {
                            try {
                                barrier.await(5, TimeUnit.SECONDS);
                                int count = kind == 2 ? mapper.touchActiveUserSession("live", 7L, 30)
                                        : mapper.touchRecentlyRotatedUserSession(kind == 0 ? "old" : "middle", 7L, 30);
                                if (count > 0) accepted.incrementAndGet();
                            } catch (Exception failure) {
                                if (deadlock(failure)) deadlocks.incrementAndGet();
                                else throw new RuntimeException(failure);
                            }
                        }
                    }));
                }
                for (var future : futures) future.get(25, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
            String evidence = System.getenv("SESSION_GRACE_EVIDENCE_DIR");
            assertThat(evidence).isNotBlank();
            Files.writeString(Path.of(evidence, "concurrency.json"),
                    "{\"attempts\":135,\"accepted\":" + accepted + ",\"deadlocks\":" + deadlocks
                            + ",\"realIndexes\":true,\"twoRotatedBearersAndLive\":true}");
            assertThat(deadlocks.get()).isZero();
            assertThat(accepted.get()).isEqualTo(135);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "window", "issued-deleted", "not-rotated", "revoked",
            "expired", "idle", "live-deleted", "different-chain", "different-owner"})
    void rejectsInvalidGraceWithoutTouchingTheSuccessor(String scenario) throws Exception {
        try (var fixture = new CanonicalEventSchemaMySqlFixture("nx_user_session"); var context = context(fixture)) {
            seed(fixture);
            fixture.jdbc().update("UPDATE nx_user_session SET last_active_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 1 DAY) WHERE refresh_token_id='live'");
            String sql = switch (scenario) {
                case "window" -> "UPDATE nx_user_session SET rotation_redeemed_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 11 SECOND) WHERE refresh_token_id='old'";
                case "issued-deleted" -> "UPDATE nx_user_session SET is_deleted=1 WHERE refresh_token_id='old'";
                case "not-rotated" -> "UPDATE nx_user_session SET rotated_to_id=NULL WHERE refresh_token_id='old'";
                case "revoked" -> "UPDATE nx_user_session SET revoked_at=UTC_TIMESTAMP() WHERE session_chain_id='chain-a'";
                case "expired" -> "UPDATE nx_user_session SET expires_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 1 SECOND) WHERE refresh_token_id='live'";
                case "idle" -> "UPDATE nx_user_session SET last_active_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 31 DAY) WHERE refresh_token_id='live'";
                case "live-deleted" -> "UPDATE nx_user_session SET is_deleted=1 WHERE refresh_token_id='live'";
                case "different-chain" -> "UPDATE nx_user_session SET session_chain_id='chain-b' WHERE refresh_token_id='live'";
                case "different-owner" -> "UPDATE nx_user_session SET user_id=8 WHERE refresh_token_id='live'";
                default -> null;
            };
            if (sql != null) fixture.jdbc().update(sql);
            var before = fixture.jdbc().queryForObject("SELECT last_active_at FROM nx_user_session WHERE refresh_token_id='live'", java.time.LocalDateTime.class);
            assertThat(context.getBean(AuthSessionMapper.class).touchRecentlyRotatedUserSession("old", scenario.equals("owner") ? 8L : 7L, 30)).isZero();
            assertThat(fixture.jdbc().queryForObject("SELECT last_active_at FROM nx_user_session WHERE refresh_token_id='live'", java.time.LocalDateTime.class)).isEqualTo(before);
        }
    }

    @Test
    void concurrentRefreshPreservesBothOldBearersAndLogoutClosesTheChain() throws Exception {
        try (var fixture = new CanonicalEventSchemaMySqlFixture("nx_user_session"); var context = context(fixture)) {
            seed(fixture);
            AuthSessionMapper mapper = context.getBean(AuthSessionMapper.class);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc().getDataSource()));
            var pool = Executors.newFixedThreadPool(3);
            var start = new CyclicBarrier(3);
            try {
                var old = pool.submit(() -> { start.await(); for (int i=0;i<25;i++) assertThat(mapper.touchRecentlyRotatedUserSession("old",7L,30)).isEqualTo(1); return true; });
                var middle = pool.submit(() -> { start.await(); for (int i=0;i<25;i++) assertThat(mapper.touchRecentlyRotatedUserSession("middle",7L,30)).isEqualTo(1); return true; });
                var refresh = pool.submit(() -> {
                    start.await();
                    for(int i=0;i<5;i++) {
                        String current=i==0 ? "live" : "next-"+(i-1), next="next-"+i;
                        tx.executeWithoutResult(status -> {
                            var row=mapper.findRefreshForUpdate(current);
                            fixture.jdbc().update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,last_active_at,expires_at) VALUES(7,?,'chain-a',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR))",next);
                            assertThat(mapper.markRefreshRotated(row.getId(),next)).isEqualTo(1);
                        });
                    }
                    return true;
                });
                assertThat(old.get(15,TimeUnit.SECONDS)).isTrue();
                assertThat(middle.get(15,TimeUnit.SECONDS)).isTrue();
                assertThat(refresh.get(15,TimeUnit.SECONDS)).isTrue();
                tx.executeWithoutResult(status -> { mapper.findRefreshForUpdate("next-4"); mapper.revokeRefreshChain("chain-a"); });
                assertThat(mapper.touchRecentlyRotatedUserSession("old",7L,30)).isZero();
                assertThat(mapper.touchRecentlyRotatedUserSession("middle",7L,30)).isZero();
                assertThat(mapper.touchActiveUserSession("next-4",7L,30)).isZero();
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"live", "middle", "old"})
    void concurrentLogoutDoesNotDeadlockWithGrace(String revokedToken) throws Exception {
        try (var fixture = new CanonicalEventSchemaMySqlFixture("nx_user_session"); var context = context(fixture)) {
            seed(fixture);
            var issuerRead = new CountDownLatch(1);
            var continueGrace = new CountDownLatch(1);
            context.getBean(SqlSessionFactory.class).getConfiguration()
                    .addInterceptor(new PauseAfterIssuerRead(issuerRead, continueGrace));
            AuthSessionMapper mapper = context.getBean(AuthSessionMapper.class);
            var tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            var pool = Executors.newSingleThreadExecutor();
            var pendingGrace = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
            try {
                tx.executeWithoutResult(status -> {
                    mapper.findRefreshForUpdate(revokedToken);
                    var grace = pool.submit(() -> mapper.touchRecentlyRotatedUserSession("old", 7L, 30));
                    try {
                        assertThat(issuerRead.await(5, TimeUnit.SECONDS)).isTrue();
                        continueGrace.countDown();
                        mapper.revokeRefreshChain("chain-a");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    pendingGrace.set(grace);
                });
                int concurrentResult = pendingGrace.get().get(5, TimeUnit.SECONDS);
                if (revokedToken.equals("live")) assertThat(concurrentResult).isZero();
                else assertThat(concurrentResult).isIn(0, 1); // Authentication may precede revocation.
                assertThat(mapper.touchRecentlyRotatedUserSession("old", 7L, 30)).isZero();
                assertThat(mapper.touchRecentlyRotatedUserSession("middle", 7L, 30)).isZero();
                assertThat(mapper.touchActiveUserSession("live", 7L, 30)).isZero();
            } finally {
                continueGrace.countDown();
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "window", "owner", "chain", "rotation"})
    void rechecksIssuerAfterTouchAndRollsBackInvalidAuthentication(String invalidation) throws Exception {
        try (var fixture = new CanonicalEventSchemaMySqlFixture("nx_user_session"); var context = context(fixture)) {
            seed(fixture);
            fixture.jdbc().update("UPDATE nx_user_session SET last_active_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 1 DAY) WHERE refresh_token_id='live'");
            var before = fixture.jdbc().queryForObject("SELECT last_active_at FROM nx_user_session WHERE refresh_token_id='live'", java.time.LocalDateTime.class);
            var touched = new AtomicBoolean();
            observe(context, (id, result) -> {
                if (!id.endsWith("touchRotatedSuccessor") || !touched.compareAndSet(false, true)) return;
                assertThat(result).isEqualTo(1);
                assertThat(fixture.jdbc().queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
                String change = switch (invalidation) {
                    case "deleted" -> "is_deleted=1";
                    case "window" -> "rotation_redeemed_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 11 SECOND)";
                    case "owner" -> "user_id=8";
                    case "chain" -> "session_chain_id='chain-b'";
                    case "rotation" -> "rotated_to_id=NULL";
                    default -> throw new IllegalArgumentException(invalidation);
                };
                independently(fixture, "UPDATE nx_user_session SET " + change + " WHERE refresh_token_id='old'");
            });
            assertThat(context.getBean(AuthSessionMapper.class).touchRecentlyRotatedUserSession("old", 7L, 30)).isZero();
            assertThat(touched).isTrue();
            assertThat(fixture.jdbc().queryForObject("SELECT last_active_at FROM nx_user_session WHERE refresh_token_id='live'", java.time.LocalDateTime.class)).isEqualTo(before);
        }
    }

    @Test
    void failedCandidateReleasesItsLockBeforeRelocating() throws Exception {
        try (var fixture = new CanonicalEventSchemaMySqlFixture("nx_user_session"); var context = context(fixture)) {
            seed(fixture);
            var rotated = new AtomicBoolean();
            var released = new AtomicBoolean();
            observe(context, (id, result) -> {
                if (id.endsWith("findActiveUserSessionInChain") && rotated.compareAndSet(false, true)) {
                    independently(fixture, "INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,last_active_at,expires_at) VALUES(7,'next','chain-a',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR))",
                            "UPDATE nx_user_session SET rotated_to_id='next',rotation_redeemed_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),revoked_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR) WHERE refresh_token_id='live'");
                } else if (id.endsWith("touchRotatedSuccessor") && Integer.valueOf(0).equals(result)) {
                    independently(fixture, "UPDATE nx_user_session SET updated_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR) WHERE refresh_token_id='live'");
                    released.set(true);
                }
            });
            assertThat(context.getBean(AuthSessionMapper.class).touchRecentlyRotatedUserSession("old", 7L, 30)).isEqualTo(1);
            assertThat(released).isTrue();
        }
    }

    @Test
    void databaseFailureAfterTouchRejectsAndRollsBack() throws Exception {
        try (var fixture = new CanonicalEventSchemaMySqlFixture("nx_user_session"); var context = context(fixture)) {
            seed(fixture);
            fixture.jdbc().update("UPDATE nx_user_session SET last_active_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 1 DAY) WHERE refresh_token_id='live'");
            var before = fixture.jdbc().queryForObject("SELECT last_active_at FROM nx_user_session WHERE refresh_token_id='live'", java.time.LocalDateTime.class);
            observe(context, (id, result) -> {
                if (id.endsWith("touchRotatedSuccessor")) throw new SQLException("Injected database failure", "08006");
            });
            assertThatThrownBy(() -> context.getBean(AuthSessionMapper.class)
                    .touchRecentlyRotatedUserSession("old", 7L, 30)).isInstanceOf(RuntimeException.class);
            assertThat(fixture.jdbc().queryForObject("SELECT last_active_at FROM nx_user_session WHERE refresh_token_id='live'", java.time.LocalDateTime.class)).isEqualTo(before);
        }
    }

    private static void independently(CanonicalEventSchemaMySqlFixture fixture, String... statements) throws SQLException {
        try (var connection = fixture.jdbc().getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET innodb_lock_wait_timeout=1");
            connection.setAutoCommit(false);
            try {
                for (String sql : statements) statement.executeUpdate(sql);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void observe(AnnotationConfigApplicationContext context, StatementObserver observer) {
        context.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(new ObserveStatement(observer));
    }

    @FunctionalInterface
    interface StatementObserver {
        void after(String id, Object result) throws Exception;
    }

    @Intercepts({@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
    static class ObserveStatement implements Interceptor {
        private final StatementObserver observer;
        ObserveStatement(StatementObserver observer) { this.observer = observer; }
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            observer.after(((MappedStatement) invocation.getArgs()[0]).getId(), result);
            return result;
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class PauseAfterIssuerRead implements Interceptor {
        private final CountDownLatch read;
        private final CountDownLatch proceed;
        private final AtomicBoolean first = new AtomicBoolean(true);

        PauseAfterIssuerRead(CountDownLatch read, CountDownLatch proceed) {
            this.read = read;
            this.proceed = proceed;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (id.endsWith("RecentUserSessionRotation") && first.compareAndSet(true, false)) {
                read.countDown();
                if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Grace test barrier timed out");
            }
            return result;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
    @MapperScan(basePackageClasses = AuthSessionMapper.class)
    static class Transactions { }

    private static AnnotationConfigApplicationContext context(CanonicalEventSchemaMySqlFixture fixture) {
        return context(fixture.jdbc().getDataSource());
    }

    static AnnotationConfigApplicationContext context(DataSource dataSource) {
        var context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.refresh();
        return context;
    }

    private static void seed(CanonicalEventSchemaMySqlFixture fixture) {
        fixture.jdbc().update("""
                INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,rotated_to_id,
                    rotation_redeemed_at,revoked_at,last_active_at,expires_at,created_at,updated_at,is_deleted)
                VALUES
                    (7,'old','chain-a','middle',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),UTC_TIMESTAMP(),UTC_TIMESTAMP(),0),
                    (7,'middle','chain-a','live',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),UTC_TIMESTAMP(),UTC_TIMESTAMP(),0),
                    (7,'live','chain-a',NULL,NULL,NULL,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)
                """);
    }

    private static boolean deadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1213) return true;
        }
        return false;
    }
}
