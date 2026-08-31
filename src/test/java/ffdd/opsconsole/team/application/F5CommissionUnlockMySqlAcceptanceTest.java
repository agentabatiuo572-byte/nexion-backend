package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class F5CommissionUnlockMySqlAcceptanceTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private String marker;
    private long eventId;
    private EventOutboxService outbox;
    private AuditLogService audit;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                System.getenv("NEXION_TEST_DB_URL"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        marker = "F5-UNLOCK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long userId = Long.parseLong(System.getenv("NEXION_TEST_USER_ID"));
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO nx_commission_event
                      (user_id,commission_type,layer_no,order_no,order_amount_usd,
                       amount_usdt,amount_nex,currency,status,unlock_at,version,remark,is_deleted)
                    VALUES (?,'network',1,?,10,10,0,'USDT','COOLING',
                            DATE_ADD(NOW(3),INTERVAL 1 DAY),0,?,0)
                    """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setString(2, marker);
            statement.setString(3, marker);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (var keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                eventId = keys.getLong(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        outbox = mock(EventOutboxService.class);
        audit = mock(AuditLogService.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM nx_commission_operation WHERE source_commission_id=?", eventId);
        jdbc.update("DELETE FROM nx_commission_event WHERE id=?", eventId);
    }

    @Test
    void dueBoundaryAndConcurrentCasUnlockExactlyOnceWithoutAnotherLedgerEntry() throws Exception {
        CommissionEventUnlockProcessor processor = processor();
        Map<String, Object> futureRow = row();
        long ledgerBefore = ledgerCount();

        assertThat(processor.unlock(futureRow)).isFalse();
        assertThat(status()).isEqualTo("COOLING");
        assertThat(operationCount()).isZero();

        jdbc.update("UPDATE nx_commission_event SET unlock_at=DATE_SUB(NOW(3),INTERVAL 1000 MICROSECOND) WHERE id=?", eventId);
        Map<String, Object> dueRow = row();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return processor.unlock(dueRow); });
            var second = pool.submit(() -> { start.await(); return processor.unlock(dueRow); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(status()).isEqualTo("UNLOCKED");
        assertThat(jdbc.queryForObject("SELECT version FROM nx_commission_event WHERE id=?", Long.class, eventId))
                .isEqualTo(1L);
        assertThat(operationCount()).isEqualTo(1L);
        assertThat(ledgerCount()).isEqualTo(ledgerBefore);
        verify(outbox, times(1)).publish(
                "COMMISSION", Long.toString(eventId), "COMMISSION_UNLOCKED",
                Map.of("user_id", dueRow.get("userId"), "commission_event_id", eventId));
        verify(audit, times(1)).recordRequired(org.mockito.ArgumentMatchers.any());
    }

    private CommissionEventUnlockProcessor processor() {
        F5CommissionMapper mapper = new SqlSessionTemplate(sessionFactory()).getMapper(F5CommissionMapper.class);
        CommissionEventUnlockProcessor target = new CommissionEventUnlockProcessor(mapper, outbox, audit);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (CommissionEventUnlockProcessor) factory.getProxy();
    }

    private SqlSessionFactory sessionFactory() {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "f5-unlock-acceptance", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.systemUTC()));
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(F5CommissionMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT id,user_id AS userId,version FROM nx_commission_event WHERE id=?", eventId);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM nx_commission_event WHERE id=?", String.class, eventId);
    }

    private long operationCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_commission_operation WHERE operation_type='AUTO_UNLOCK' AND source_commission_id=?",
                Long.class, eventId);
    }

    private long ledgerCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_wallet_ledger WHERE remark LIKE ?", Long.class, "%" + marker + "%");
    }
}
