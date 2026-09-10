package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Opt-in isolated-MySQL proof for the lease-expiry transaction, never a production database test. */
@EnabledIfEnvironmentVariable(named = "NEXION_DEV_PENDING_IT", matches = "true")
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class AppTaskAssignmentLeaseExpirySpringTransactionMySqlIntegrationTest {
    private static final String SERVER_URL = "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8"
            + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    private static final Pattern OWNED_DATABASE = Pattern.compile("nx_task_expiry_test_[0-9a-f]{32}");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
    private Connection admin;
    private DataSource source;
    private JdbcTemplate jdbc;
    private String database;
    private boolean created;
    private java.util.Map<String, java.util.List<java.util.Map<String, Object>>> rewardSnapshot;

    @BeforeEach
    void createFixture() throws Exception {
        admin = DriverManager.getConnection(SERVER_URL, "root", System.getenv("NEXION_TEST_DB_PASSWORD"));
        assertThat(new JdbcTemplate(new DriverManagerDataSource(SERVER_URL, "root", System.getenv("NEXION_TEST_DB_PASSWORD")))
                .queryForObject("SELECT DATABASE()", String.class)).isNull();
        database = "nx_task_expiry_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(OWNED_DATABASE.matcher(database).matches()).isTrue();
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        source = new DriverManagerDataSource(fixtureUrl(database), "root", System.getenv("NEXION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        createTables();
        seed();
        rewardSnapshot = rewardRows();
    }

    @AfterEach
    void discardFixture() throws Exception {
        try {
            if (created && database != null && OWNED_DATABASE.matcher(database).matches()) {
                try (var statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
                }
            }
        } finally {
            if (admin != null) admin.close();
        }
    }

    @Test
    void outboxFailureRollsBackExpiredTaskRuntimeAndPendingDeviceWithoutRewards() {
        EventOutboxService outbox = mock(EventOutboxService.class);
        doThrow(new IllegalStateException("TEST_OUTBOX_UNAVAILABLE"))
                .when(outbox).publish(anyString(), anyString(), anyString(), any());
        AuditLogService audit = mock(AuditLogService.class);

        assertThatThrownBy(() -> inTransaction(outbox, audit))
                .hasMessage("TEST_OUTBOX_UNAVAILABLE");

        verifyNoInteractions(audit);
        assertRollbackState();
    }

    @Test
    void deviceAuditFailureRollsBackBothOutboxRowsAndEveryLeaseMutation() {
        TransactionalOutbox outbox = new TransactionalOutbox(jdbc);
        AuditLogService audit = mock(AuditLogService.class);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("TEST_DEVICE_AUDIT_UNAVAILABLE");
            return null;
        }).when(audit).recordRequiredForTrustedActor(any());

        assertThatThrownBy(() -> inTransaction(outbox, audit))
                .hasMessage("TEST_DEVICE_AUDIT_UNAVAILABLE");

        assertThat(calls.get()).isEqualTo(2);
        assertRollbackState();
    }

    @Test
    void successfulExpiryUsesMybatisDeviceStateAndLeavesNoReceiptOrReward() {
        TransactionalOutbox outbox = new TransactionalOutbox(jdbc);
        inTransaction(outbox, mock(AuditLogService.class));

        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no='CTA-EXPIRED'", String.class))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=11", String.class))
                .isEqualTo("DEACTIVATED");
        assertThat(jdbc.queryForObject("SELECT row_version FROM nx_user_device WHERE id=11", Long.class)).isEqualTo(8L);
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime WHERE user_device_id=11", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime WHERE user_device_id=11", String.class))
                .isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_outbox", Integer.class)).isEqualTo(2);
        assertRewardRowsUnchanged();
    }

    @Test
    void controlledMybatisCasZeroRollsBackTheRealExpiryTransactionSoTheTaskRemainsRetryable() {
        AppTaskAssignmentMapper raw = new SqlSessionTemplate(sessionFactory()).getMapper(AppTaskAssignmentMapper.class);
        AppTaskAssignmentMapper casZero = (AppTaskAssignmentMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AppTaskAssignmentMapper.class}, (proxy, method, args) -> {
                    if ("deactivatePendingDeviceAfterLeaseExpiry".equals(method.getName())) return 0;
                    try {
                        return method.invoke(raw, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });

        assertThatThrownBy(() -> inTransaction(casZero, new TransactionalOutbox(jdbc), mock(AuditLogService.class)))
                .hasMessage("TASK_ASSIGNMENT_DEFERRED_DEACTIVATION_CAS_CONFLICT");

        assertRollbackState();
    }

    private void inTransaction(EventOutboxService outbox, AuditLogService audit) {
        inTransaction(new SqlSessionTemplate(sessionFactory()).getMapper(AppTaskAssignmentMapper.class), outbox, audit);
    }

    private void inTransaction(AppTaskAssignmentMapper mapper, EventOutboxService outbox, AuditLogService audit) {
        assertThat(transactionalService(mapper, outbox, audit).expirePendingLease(7L, 11L, "CTA-EXPIRED")).isTrue();
    }

    private AppTaskAssignmentService transactionalService(
            AppTaskAssignmentMapper mapper, EventOutboxService outbox, AuditLogService audit) {
        var proof = mock(ComputeTaskProofVerifier.class);
        org.mockito.Mockito.when(proof.sourceEnvironment()).thenReturn("PRODUCTION");
        AppTaskAssignmentService target = new AppTaskAssignmentService(mapper,
                mock(AdminIdempotencyService.class), outbox, audit, proof, new StandardEnvironment(), CLOCK);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        return (AppTaskAssignmentService) factory.getProxy();
    }

    private SqlSessionFactory sessionFactory() {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "lease-expiry", new SpringManagedTransactionFactory(), source));
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppTaskAssignmentMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void assertRollbackState() {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no='CTA-EXPIRED'", String.class))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=11", String.class)).isEqualTo("BUSY");
        assertThat(jdbc.queryForObject("SELECT pending_deactivate FROM nx_user_device WHERE id=11", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT row_version FROM nx_user_device WHERE id=11", Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime WHERE user_device_id=11", String.class))
                .isEqualTo("CTA-EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_outbox", Integer.class)).isZero();
        assertRewardRowsUnchanged();
    }

    private void assertRewardRowsUnchanged() {
        assertThat(rewardRows()).isEqualTo(rewardSnapshot);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_compute_receipt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=7", java.math.BigDecimal.class))
                .isEqualByComparingTo("2.000000");
        assertThat(jdbc.queryForObject("SELECT lifetime_earned FROM nx_user_wallet WHERE user_id=7", java.math.BigDecimal.class))
                .isEqualByComparingTo("5.000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no='EXISTING'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earning_event WHERE event_no='EARN-EXISTING'", Integer.class))
                .isEqualTo(1);
    }

    private java.util.Map<String, java.util.List<java.util.Map<String, Object>>> rewardRows() {
        return java.util.Map.of(
                "wallet", jdbc.queryForList("SELECT * FROM nx_user_wallet ORDER BY id"),
                "ledger", jdbc.queryForList("SELECT * FROM nx_wallet_ledger ORDER BY id"),
                "earning", jdbc.queryForList("SELECT * FROM nx_earning_event ORDER BY id"));
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox TINYINT,is_deleted TINYINT,created_at DATETIME)");
        jdbc.execute("CREATE TABLE nx_user_device (id BIGINT PRIMARY KEY,user_id BIGINT,instance_no VARCHAR(96),status VARCHAR(32),row_version BIGINT,pending_deactivate TINYINT,source_environment VARCHAR(32),run_id VARCHAR(96),ownership_status VARCHAR(32),activated_at DATETIME,deactivated_at DATETIME,updated_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_task (id BIGINT AUTO_INCREMENT PRIMARY KEY,task_no VARCHAR(96),user_id BIGINT,user_device_id BIGINT,task_config_id VARCHAR(96),task_name VARCHAR(128),task_type VARCHAR(32),model_name VARCHAR(128),client_name VARCHAR(128),status VARCHAR(32),reward_usdt DECIMAL(18,6),required_seconds INT,task_lock_minutes INT,started_at DATETIME,lease_expires_at DATETIME,completed_at DATETIME,completion_nonce VARCHAR(128),proof_expires_at DATETIME,proof_consumed_at DATETIME,source_environment VARCHAR(32),last_error VARCHAR(128),updated_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_receipt (id BIGINT AUTO_INCREMENT PRIMARY KEY,task_no VARCHAR(96),receipt_no VARCHAR(96),source_environment VARCHAR(32),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_user_device_runtime (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_device_id BIGINT,active_task_no VARCHAR(96),online_status VARCHAR(32),paused_reason VARCHAR(64),updated_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_config_item (id BIGINT AUTO_INCREMENT PRIMARY KEY,config_key VARCHAR(128),config_value VARCHAR(128),status TINYINT,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_user_wallet (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,usdt_available DECIMAL(18,6),lifetime_earned DECIMAL(18,6),version BIGINT,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_wallet_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,biz_no VARCHAR(128),asset VARCHAR(16),direction VARCHAR(16),amount DECIMAL(18,6),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_earning_event (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_no VARCHAR(128),user_id BIGINT,user_device_id BIGINT,amount DECIMAL(18,6),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE fixture_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_type VARCHAR(128),payload TEXT)");
    }

    private void seed() {
        jdbc.update("INSERT INTO nx_user(id,status,sandbox,is_deleted,created_at) VALUES(7,'ACTIVE',0,0,'2026-08-01 00:00:00')");
        jdbc.update("INSERT INTO nx_user_device(id,user_id,instance_no,status,row_version,pending_deactivate,source_environment,run_id,ownership_status,activated_at,deactivated_at,updated_at,is_deleted) VALUES(11,7,'DEV-11','BUSY',7,1,'PRODUCTION','','OWNED','2026-08-01 00:00:00',NULL,'2026-09-10 11:00:00',0)");
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_config_id,task_name,task_type,model_name,client_name,status,reward_usdt,required_seconds,task_lock_minutes,started_at,lease_expires_at,completed_at,completion_nonce,proof_expires_at,proof_consumed_at,source_environment,last_error,updated_at,is_deleted) VALUES('CTA-EXPIRED',7,11,'TASK-IG','Canonical IG','IG','model','Nexion App','RUNNING',0.3,18,30,'2026-09-08 12:00:00','2026-09-09 12:00:00',NULL,'nonce','2026-09-09 12:00:00',NULL,'PRODUCTION',NULL,'2026-09-10 11:00:00',0)");
        jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,active_task_no,online_status,paused_reason,updated_at,is_deleted) VALUES(11,'CTA-EXPIRED','ONLINE',NULL,'2026-09-10 11:00:00',0)");
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,lifetime_earned,version,is_deleted) VALUES(7,2,5,1,0)");
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,asset,direction,amount,is_deleted) VALUES(7,'EXISTING','USDT','IN',1,0)");
        jdbc.update("INSERT INTO nx_earning_event(event_no,user_id,user_device_id,amount,is_deleted) VALUES('EARN-EXISTING',7,11,1,0)");
    }

    private static String fixtureUrl(String schema) {
        return SERVER_URL.substring(0, SERVER_URL.indexOf('?')) + schema + SERVER_URL.substring(SERVER_URL.indexOf('?'));
    }

    /** Transaction-bound persistence stub for proving rollback, rather than a substitute for EventOutboxService behavior. */
    private static final class TransactionalOutbox extends EventOutboxService {
        private final JdbcTemplate jdbc;

        private TransactionalOutbox(JdbcTemplate jdbc) {
            super(null, null, null, null);
            this.jdbc = jdbc;
        }

        @Override
        public String publish(String aggregateType, String aggregateId, String eventType, Object payload) {
            jdbc.update("INSERT INTO fixture_outbox(event_type,payload) VALUES(?,?)", eventType, String.valueOf(payload));
            return UUID.randomUUID().toString();
        }

        @Override
        public String publishUserEvent(String aggregateType, String aggregateId, String eventType, Long userId,
                                       String phase, Integer accountAgeMonths, String cohort, Object payload) {
            jdbc.update("INSERT INTO fixture_outbox(event_type,payload) VALUES(?,?)", eventType, String.valueOf(payload));
            return UUID.randomUUID().toString();
        }
    }
}
