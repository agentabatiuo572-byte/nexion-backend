package ffdd.opsconsole.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class AdminIdempotencyExpiryTransitionSpringTransactionMySqlIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void recoverySkipsARequestOwnedExpiredRowThenCollectsItAfterFinalization() throws Exception {
        DataSource dataSource = dataSource();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long requestOwned = insertExpiredProcessing(dataSource, "A1_PASSWORD_RESET:IT", "idem-owned-" + suffix);
        long recoverable = insertExpiredProcessing(dataSource, "A1_PASSWORD_RESET:IT", "idem-stale-" + suffix);
        AdminIdempotencyExpiryTransitionExecutor transition = transitionExecutor(dataSource);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection owner = dataSource.getConnection()) {
            owner.setAutoCommit(false);
            try (PreparedStatement lock = owner.prepareStatement(
                    "SELECT id FROM nx_admin_idempotency_record WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, requestOwned);
                try (ResultSet ignored = lock.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }

            var firstSweep = pool.submit(() -> transition.markExpiredProcessingUnknownBatch(10));
            assertThat(firstSweep.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(status(dataSource, requestOwned)).isEqualTo("PROCESSING");
            assertThat(status(dataSource, recoverable)).isEqualTo("UNKNOWN");

            owner.commit();
            assertThat(transition.markExpiredProcessingUnknownBatch(10)).isEqualTo(1);
            assertThat(status(dataSource, requestOwned)).isEqualTo("UNKNOWN");
        } finally {
            pool.shutdownNow();
            deleteFixture(dataSource, requestOwned);
            deleteFixture(dataSource, recoverable);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void unknownTransitionCommitsWhenTheOuterClaimRollsBack() throws Exception {
        DataSource dataSource = dataSource();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String scope = "A6_ROLE_GRANTS:IT";
        String key = "idem-expiry-it-" + suffix;
        long recordId = insertExpiredProcessing(dataSource, scope, key);

        try {
            AdminIdempotencyService service = idempotencyService(dataSource);

            assertThatThrownBy(() -> service.execute(scope, key, "a".repeat(64), Map.class, Map::of))
                    .isInstanceOf(BizException.class)
                    .hasMessage("IDEMPOTENCY_RESULT_UNKNOWN");

            assertThat(status(dataSource, recordId)).isEqualTo("UNKNOWN");
        } finally {
            deleteFixture(dataSource, recordId);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void databaseNowWinsAcrossJvmTimezoneAndTwoInstancesRaceSafely() throws Exception {
        DataSource dataSource = dataSource();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String scope = "J4_PLAYBOOK_EXECUTE:IT";
        String key = "idem-expiry-zone-" + suffix;
        long recordId = insertProcessing(dataSource, scope, key, "DATE_ADD(NOW(), INTERVAL 1 MINUTE)");
        AdminIdempotencyService tokyoJvmService = idempotencyService(dataSource);

        try {
            // The JVM Clock is +09 while MySQL NOW() is the acceptance-server
            // clock. A still-live database lease must remain in-progress.
            assertThatThrownBy(() -> tokyoJvmService.execute(
                    scope, key, "a".repeat(64), Map.class, Map::of))
                    .isInstanceOf(BizException.class)
                    .hasMessage("IDEMPOTENCY_REQUEST_IN_PROGRESS");
            assertThat(status(dataSource, recordId)).isEqualTo("PROCESSING");

            expire(dataSource, recordId);
            AdminIdempotencyService firstInstance = idempotencyService(dataSource);
            AdminIdempotencyService secondInstance = idempotencyService(dataSource);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<String> first = () -> executeAndCapture(firstInstance, scope, key, start);
                Callable<String> second = () -> executeAndCapture(secondInstance, scope, key, start);
                var futureOne = pool.submit(first);
                var futureTwo = pool.submit(second);
                start.countDown();
                assertThat(futureOne.get(20, TimeUnit.SECONDS)).isEqualTo("IDEMPOTENCY_RESULT_UNKNOWN");
                assertThat(futureTwo.get(20, TimeUnit.SECONDS)).isEqualTo("IDEMPOTENCY_RESULT_UNKNOWN");
            } finally {
                pool.shutdownNow();
            }
            assertThat(status(dataSource, recordId)).isEqualTo("UNKNOWN");
        } finally {
            deleteFixture(dataSource, recordId);
        }
    }

    private String executeAndCapture(
            AdminIdempotencyService service, String scope, String key, CountDownLatch start) throws Exception {
        assertThat(start.await(20, TimeUnit.SECONDS)).isTrue();
        try {
            service.execute(scope, key, "a".repeat(64), Map.class, Map::of);
            throw new AssertionError("expired processing key must not execute");
        } catch (BizException expected) {
            return expected.getMessage();
        }
    }

    private AdminIdempotencyService idempotencyService(DataSource dataSource) {
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(dataSource));
        AdminIdempotencyRecordMapper mapper = template.getMapper(AdminIdempotencyRecordMapper.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                manager, new AnnotationTransactionAttributeSource());

        AdminIdempotencyExpiryTransitionExecutor transition = proxied(
                new AdminIdempotencyExpiryTransitionExecutor(mapper), interceptor,
                AdminIdempotencyExpiryTransitionExecutor.class);
        AdminIdempotencyTransactionExecutor executor = proxied(
                new AdminIdempotencyTransactionExecutor(
                        mapper, new ObjectMapper().findAndRegisterModules(), transition),
                interceptor, AdminIdempotencyTransactionExecutor.class);
        return new AdminIdempotencyService(
                executor, Clock.fixed(java.time.Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private AdminIdempotencyExpiryTransitionExecutor transitionExecutor(DataSource dataSource) {
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(dataSource));
        AdminIdempotencyRecordMapper mapper = template.getMapper(AdminIdempotencyRecordMapper.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                manager, new AnnotationTransactionAttributeSource());
        return proxied(new AdminIdempotencyExpiryTransitionExecutor(mapper), interceptor,
                AdminIdempotencyExpiryTransitionExecutor.class);
    }

    private <T> T proxied(T target, TransactionInterceptor interceptor, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        Configuration configuration = new Configuration(new Environment(
                "idempotency-expiry-transaction", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AdminIdempotencyRecordMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        String url = System.getenv().getOrDefault(
                "NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        return new DriverManagerDataSource(
                url,
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
    }

    private long insertExpiredProcessing(DataSource dataSource, String scope, String key) throws Exception {
        return insertProcessing(dataSource, scope, key, "DATE_SUB(NOW(), INTERVAL 1 MINUTE)");
    }

    private long insertProcessing(DataSource dataSource, String scope, String key, String expiresAtSql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(("""
                    INSERT INTO nx_admin_idempotency_record (
                        scope, idempotency_key, request_hash, status, expires_at,
                        created_at, updated_at, is_deleted
                    ) VALUES (?, ?, ?, 'PROCESSING', %s, NOW(), NOW(), 0)
                    """.formatted(expiresAtSql)), java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            statement.setString(3, "a".repeat(64));
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private void expire(DataSource dataSource, long recordId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE nx_admin_idempotency_record SET expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?")) {
            statement.setLong(1, recordId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private String status(DataSource dataSource, long recordId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM nx_admin_idempotency_record WHERE id = ?")) {
            statement.setLong(1, recordId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void deleteFixture(DataSource dataSource, long recordId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM nx_admin_idempotency_record WHERE id = ?")) {
            statement.setLong(1, recordId);
            statement.executeUpdate();
        }
    }
}
