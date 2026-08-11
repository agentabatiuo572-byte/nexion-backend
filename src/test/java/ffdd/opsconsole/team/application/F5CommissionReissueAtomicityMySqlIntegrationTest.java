package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.team.dto.F5CommissionReissueRequest;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class F5CommissionReissueAtomicityMySqlIntegrationTest {
    private static final String IDEMPOTENCY_SCOPE = "F5_COMMISSION_REISSUE";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private String marker;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        marker = "F5-ATOMIC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS nx_f5_reissue_atomicity_probe (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  fixture VARCHAR(64) NOT NULL,
                  biz_no VARCHAR(128) NOT NULL,
                  amount DECIMAL(18,6) NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_f5_reissue_probe_biz_no (biz_no)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=DATABASE()
                   AND TABLE_NAME='nx_commission_operation'
                   AND INDEX_NAME='uk_commission_reissue_source'
                """, Integer.class)).isEqualTo(1);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM nx_commission_operation WHERE reason = ?", marker);
        jdbc.update("DELETE FROM nx_commission_event WHERE remark LIKE ?", "%" + marker + "%");
        jdbc.update("DELETE FROM nx_f5_reissue_atomicity_probe WHERE fixture = ?", marker);
        for (String key : keys) {
            jdbc.update("DELETE FROM nx_admin_idempotency_record WHERE scope = ? AND idempotency_key = ?",
                    IDEMPOTENCY_SCOPE, key);
        }
        A2ReplayContext.exitReplay();
    }

    @Test
    void secondSourceConflictLeavesNoEventLedgerOrOperationAndDoesNotSucceedIdempotency() {
        long first = insertSource("REVERSED", 1);
        long second = insertSource("UNLOCKED", 2);
        String key = key("preflight-conflict");
        F5CommissionService service = service(0);

        assertThatThrownBy(() -> invoke(service, key, first, second))
                .isInstanceOf(BizException.class)
                .hasMessage("COMMISSION_REISSUE_STATE_CONFLICT:CM-" + second);

        assertNoEffects(first, second);
        assertThat(idempotencyStatus(key)).isEqualTo("FAILED");
    }

    @Test
    void secondLedgerWriteFailureRollsBackTheWholePrefixAndMarksIdempotencyFailed() {
        long first = insertSource("REVERSED", 3);
        long second = insertSource("REVERSED", 4);
        String key = key("write-failure");
        F5CommissionService service = service(2);

        assertThatThrownBy(() -> invoke(service, key, first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("F5_TEST_LEDGER_WRITE_FAILED");

        assertNoEffects(first, second);
        assertThat(idempotencyStatus(key)).isEqualTo("FAILED");
    }

    @Test
    void successReplaysWithoutDuplicatingEffectsAndDifferentKeyCannotReissueTheSameSource() {
        long first = insertSource("REVERSED", 5);
        long second = insertSource("REVERSED", 6);
        String successKey = key("success-replay");
        F5CommissionService service = service(0);

        ApiResult<Map<String, Object>> firstResponse = invoke(service, successKey, second, first);
        ApiResult<Map<String, Object>> replayResponse = invoke(service, successKey, first, second);

        assertThat(firstResponse.getCode()).isZero();
        assertThat(replayResponse.getCode()).isZero();
        assertThat(replayResponse.getData().get("batchNo"))
                .isEqualTo(firstResponse.getData().get("batchNo"));
        assertThat(effectCounts(first, second)).containsExactly(2L, 2L, 2L);
        assertThat(idempotencyStatus(successKey)).isEqualTo("SUCCEEDED");

        String differentKey = key("duplicate-source");
        assertThatThrownBy(() -> invoke(service(0), differentKey, first))
                .isInstanceOf(BizException.class)
                .hasMessage("COMMISSION_REISSUE_ALREADY_CONSUMED:CM-" + first);
        assertThat(effectCounts(first, second)).containsExactly(2L, 2L, 2L);
        assertThat(idempotencyStatus(differentKey)).isEqualTo("FAILED");
    }

    @Test
    void concurrentDifferentKeysForOneSourceProduceExactlyOneCommittedBatch() throws Exception {
        long source = insertSource("REVERSED", 7);
        String firstKey = key("race-a");
        String secondKey = key("race-b");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> raceOutcome(service(0), firstKey, source, start));
            var second = pool.submit(() -> raceOutcome(service(0), secondKey, source, start));
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "COMMISSION_REISSUE_ALREADY_CONSUMED:CM-" + source);
        } finally {
            pool.shutdownNow();
        }

        assertThat(effectCounts(source)).containsExactly(1L, 1L, 1L);
        assertThat(List.of(idempotencyStatus(firstKey), idempotencyStatus(secondKey)))
                .containsExactlyInAnyOrder("SUCCEEDED", "FAILED");
    }

    private String raceOutcome(
            F5CommissionService service, String key, long source, CountDownLatch start) throws Exception {
        assertThat(start.await(20, TimeUnit.SECONDS)).isTrue();
        try {
            invoke(service, key, source);
            return "SUCCESS";
        } catch (BizException expected) {
            return expected.getMessage();
        } finally {
            A2ReplayContext.exitReplay();
        }
    }

    private F5CommissionService service(int failOnLedgerCall) {
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory());
        F5CommissionMapper commissionMapper = template.getMapper(F5CommissionMapper.class);
        AdminIdempotencyRecordMapper idempotencyMapper = template.getMapper(AdminIdempotencyRecordMapper.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                manager, new AnnotationTransactionAttributeSource());
        AdminIdempotencyExpiryTransitionExecutor transition = proxied(
                new AdminIdempotencyExpiryTransitionExecutor(idempotencyMapper), interceptor,
                AdminIdempotencyExpiryTransitionExecutor.class);
        AdminIdempotencyTransactionExecutor executor = proxied(
                new AdminIdempotencyTransactionExecutor(
                        idempotencyMapper, new ObjectMapper().findAndRegisterModules(), transition),
                interceptor, AdminIdempotencyTransactionExecutor.class);
        AdminIdempotencyService idempotency = new AdminIdempotencyService(executor, Clock.systemUTC());

        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(java.util.Optional.empty());
        TreasuryCoverageFacade coverage = () -> new TreasuryCoverageSnapshot(
                new BigDecimal("1.20"), new BigDecimal("0.85"));
        AtomicInteger calls = new AtomicInteger();
        TreasuryLedgerPostingFacade ledger = (bizNo, userId, bizType, asset, direction, amount, status, remark) -> {
            jdbc.update("INSERT INTO nx_f5_reissue_atomicity_probe(fixture, biz_no, amount) VALUES (?, ?, ?)",
                    marker, bizNo, amount);
            if (failOnLedgerCall > 0 && calls.incrementAndGet() == failOnLedgerCall) {
                throw new IllegalStateException("F5_TEST_LEDGER_WRITE_FAILED");
            }
        };
        return new F5CommissionService(
                commissionMapper, config, coverage, ledger,
                mock(AuditLogService.class), mock(EventOutboxService.class), idempotency);
    }

    private ApiResult<Map<String, Object>> invoke(F5CommissionService service, String key, long... sourceIds) {
        A2ReplayContext.enterReplay("A2-" + marker);
        List<String> ids = java.util.Arrays.stream(sourceIds).mapToObj(id -> "CM-" + id).toList();
        return service.reissue(key, new F5CommissionReissueRequest(ids, marker, "integration-test"));
    }

    private long insertSource(String status, int sequence) {
        String sql = """
                INSERT INTO nx_commission_event
                  (user_id, commission_type, source_user_id, source_user_name, layer_no,
                   order_no, order_amount_usd, amount_usdt, amount_nex, currency, status,
                   unlock_at, remark, is_deleted)
                VALUES (?, 'network', NULL, NULL, 1, ?, 10.000000, 10.000000, 0, 'USDT', ?, NULL, ?, 0)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, 9_000_000_000L + sequence);
            statement.setString(2, marker + "-SOURCE-" + sequence);
            statement.setString(3, status);
            statement.setString(4, marker);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet generated = statement.getGeneratedKeys()) {
                assertThat(generated.next()).isTrue();
                return generated.getLong(1);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void assertNoEffects(long... sourceIds) {
        assertThat(effectCounts(sourceIds)).containsExactly(0L, 0L, 0L);
    }

    private List<Long> effectCounts(long... sourceIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(sourceIds.length, "?"));
        Object[] args = java.util.Arrays.stream(sourceIds).boxed().toArray();
        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_commission_event WHERE remark LIKE ? AND remark LIKE 'F5 reissue from CM-%'",
                Long.class, "%" + marker + "%");
        Long ledgers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_f5_reissue_atomicity_probe WHERE fixture = ?", Long.class, marker);
        Long operations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_commission_operation WHERE operation_type='REISSUE'"
                        + " AND source_commission_id IN (" + placeholders + ")",
                Long.class, args);
        return List.of(events, ledgers, operations);
    }

    private String idempotencyStatus(String key) {
        return jdbc.queryForObject(
                "SELECT status FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",
                String.class, IDEMPOTENCY_SCOPE, key);
    }

    private String key(String suffix) {
        String key = marker + "-" + suffix;
        keys.add(key);
        return key;
    }

    private SqlSessionFactory sessionFactory() {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "f5-reissue-atomicity", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        globalConfig.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.systemUTC()));
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(F5CommissionMapper.class);
        configuration.addMapper(AdminIdempotencyRecordMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private <T> T proxied(T target, TransactionInterceptor interceptor, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
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
}
