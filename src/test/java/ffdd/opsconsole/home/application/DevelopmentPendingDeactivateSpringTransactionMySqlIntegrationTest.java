package ffdd.opsconsole.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in proof that observability failures roll back the isolated pending-device settlement. */
@EnabledIfEnvironmentVariable(named = "NEXION_DEV_PENDING_IT", matches = "true")
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class DevelopmentPendingDeactivateSpringTransactionMySqlIntegrationTest {
    private static final Pattern OWNED_DATABASE = Pattern.compile("nx_dev_pending_device_test_[0-9a-f]{32}");
    private static final Pattern LOOPBACK_SERVER_URL = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final String FIXTURE_SERVER_URL =
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private Connection admin;
    private DataSource source;
    private JdbcTemplate jdbc;
    private String database;
    private boolean created;

    @BeforeEach
    void createOwnedFixture() throws Exception {
        String serverUrl = FIXTURE_SERVER_URL;
        requireLoopbackServerUrl(serverUrl);
        String username = "root";
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        admin = DriverManager.getConnection(serverUrl, username, password);
        assertThat(new JdbcTemplate(new DriverManagerDataSource(serverUrl, username, password))
                .queryForObject("SELECT DATABASE()", String.class)).isNull();
        database = "nx_dev_pending_device_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(OWNED_DATABASE.matcher(database).matches()).isTrue();
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        source = new DriverManagerDataSource(fixtureUrl(serverUrl, database), username, password);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        createTables();
        seedPendingRunningTask();
    }

    @AfterEach
    void discardOwnedFixture() throws Exception {
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
    void auditFailureRollsBackCompletionRewardAndPendingDeviceDeactivationWithNoRuntimeRow() {
        AuditLogService audit = mock(AuditLogService.class);
        TransactionalOutbox outbox = new TransactionalOutbox(jdbc);
        doThrow(new IllegalStateException("TEST_AUDIT_UNAVAILABLE"))
                .when(audit).recordRequiredForTrustedActor(any());

        assertThat(worker(outbox, audit).advanceTasks()).isZero();

        verify(audit).recordRequiredForTrustedActor(any());
        assertRollbackState();
    }

    @Test
    void outboxFailureRollsBackCompletionRewardAndPendingDeviceDeactivationWithNoRuntimeRow() {
        EventOutboxService outbox = mock(EventOutboxService.class);
        doThrow(new IllegalStateException("TEST_OUTBOX_UNAVAILABLE"))
                .when(outbox).publishUserEvent(anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyString(), any());

        assertThat(worker(outbox, mock(AuditLogService.class)).advanceTasks()).isZero();

        verify(outbox).publishUserEvent(anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyString(), any());
        assertRollbackState();
    }

    @Test
    void successfulCompletionUsesMappedBusyStatusAndSettlementTriggerWithoutRuntimeRow() {
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);

        assertThat(worker(outbox, audit).advanceTasks()).isEqualTo(2);

        assertDeviceDeactivated();
        ArgumentCaptor<Object> eventPayload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publishUserEvent(anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyString(),
                eventPayload.capture());
        assertState(asMap(eventPayload.getValue()), "BUSY");
        ArgumentCaptor<AuditLogWriteRequest> auditRequest = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequiredForTrustedActor(auditRequest.capture());
        assertThat(auditRequest.getValue().getActorType()).isEqualTo("SYSTEM");
        assertThat(auditRequest.getValue().getActorId()).isZero();
        Map<String, Object> detail = asMap(auditRequest.getValue().getDetail());
        assertThat(detail.get("trigger")).isEqualTo("TASK_SETTLEMENT_COMPLETED");
        assertState(asMap(detail.get("state")), "BUSY");
    }

    @Test
    void noActiveTaskUsesMappedOnlineStatusAndNoActiveTrigger() {
        jdbc.update("DELETE FROM nx_compute_task");
        jdbc.update("UPDATE nx_user_device SET status='ONLINE' WHERE id=8101");
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);

        assertThat(worker(outbox, audit).advanceTasks()).isEqualTo(1);

        assertDeviceDeactivated();
        ArgumentCaptor<Object> eventPayload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publishUserEvent(anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyString(),
                eventPayload.capture());
        assertState(asMap(eventPayload.getValue()), "ONLINE");
        ArgumentCaptor<AuditLogWriteRequest> auditRequest = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequiredForTrustedActor(auditRequest.capture());
        Map<String, Object> detail = asMap(auditRequest.getValue().getDetail());
        assertThat(detail.get("trigger")).isEqualTo("PENDING_WITHOUT_ACTIVE_TASK");
        assertState(asMap(detail.get("state")), "ONLINE");
    }

    private void assertRollbackState() {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=8101", String.class)).isEqualTo("BUSY");
        assertThat(jdbc.queryForObject("SELECT pending_deactivate FROM nx_user_device WHERE id=8101", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT row_version FROM nx_user_device WHERE id=8101", Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no='DEV-TASK-ROLLBACK'", String.class))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_compute_receipt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=91", java.math.BigDecimal.class))
                .isEqualByComparingTo("2.000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earning_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime", Integer.class)).isZero();
    }

    private void assertDeviceDeactivated() {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=8101", String.class)).isEqualTo("DEACTIVATED");
        assertThat(jdbc.queryForObject("SELECT pending_deactivate FROM nx_user_device WHERE id=8101", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT row_version FROM nx_user_device WHERE id=8101", Long.class)).isEqualTo(8L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime", Integer.class)).isZero();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static void assertState(Map<String, Object> state, String previousStatus) {
        assertThat(state).containsEntry("userId", 91L).containsEntry("deviceId", 8101L)
                .containsEntry("instanceNo", "DEV-8101").containsEntry("previousStatus", previousStatus)
                .containsEntry("status", "DEACTIVATED").containsEntry("rowVersion", 8L);
    }

    private DevelopmentHomeSettlementBootstrap worker(EventOutboxService outbox, AuditLogService audit) {
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(source));
        return new DevelopmentHomeSettlementBootstrap(template.getMapper(DevelopmentHomeSettlementMapper.class), CLOCK,
                "+86", "18708173775", true, outbox, audit, new DataSourceTransactionManager(source));
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "development-pending-device", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DevelopmentHomeSettlementMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,sandbox TINYINT NOT NULL,status VARCHAR(32) NOT NULL,created_at DATETIME NOT NULL,is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_user_device (id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,instance_no VARCHAR(96) NOT NULL,name VARCHAR(128),gpu_model VARCHAR(128),product_code VARCHAR(96),device_type VARCHAR(32),status VARCHAR(32),row_version BIGINT NOT NULL,vram_total_gb INT,purchased_at DATETIME,activated_at DATETIME,deactivated_at DATETIME,daily_usdt DECIMAL(18,6),pending_deactivate TINYINT NOT NULL DEFAULT 0,source_environment VARCHAR(32),run_id VARCHAR(96),source_channel VARCHAR(64),source_order_no VARCHAR(96),ownership_status VARCHAR(32),is_deleted TINYINT NOT NULL DEFAULT 0,updated_at DATETIME)");
        jdbc.execute("CREATE TABLE nx_compute_task (id BIGINT AUTO_INCREMENT PRIMARY KEY,task_no VARCHAR(96) NOT NULL,user_id BIGINT NOT NULL,user_device_id BIGINT NOT NULL,task_type VARCHAR(32),task_config_id VARCHAR(96),task_name VARCHAR(128),model_name VARCHAR(128),reward_usdt DECIMAL(18,6),required_seconds INT,task_lock_minutes INT,completion_nonce VARCHAR(128),proof_expires_at DATETIME,source_environment VARCHAR(32),client_name VARCHAR(128),status VARCHAR(32),started_at DATETIME,worker_ack_at DATETIME,lease_expires_at DATETIME,proof_consumed_at DATETIME,completed_at DATETIME,attempt_count INT,max_attempts INT,created_at DATETIME,updated_at DATETIME,is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_compute_receipt (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,task_no VARCHAR(96),receipt_no VARCHAR(128),task_type VARCHAR(32),client_name VARCHAR(128),reward_usdt DECIMAL(18,6),reward_nex DECIMAL(18,6),earning_status VARCHAR(32),source_environment VARCHAR(32),proof_hash VARCHAR(128),completed_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted TINYINT NOT NULL DEFAULT 0,UNIQUE KEY uk_receipt_task(task_no),UNIQUE KEY uk_receipt_no(receipt_no))");
        jdbc.execute("CREATE TABLE nx_user_wallet (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,usdt_available DECIMAL(18,6),nex_available DECIMAL(18,6),pending_withdraw DECIMAL(18,6),lifetime_earned DECIMAL(18,6),cumulative_deposit_usdt DECIMAL(18,6),version BIGINT,created_at DATETIME,updated_at DATETIME,is_deleted TINYINT NOT NULL DEFAULT 0,UNIQUE KEY uk_wallet_user(user_id))");
        jdbc.execute("CREATE TABLE nx_wallet_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,biz_no VARCHAR(128),biz_type VARCHAR(64),asset VARCHAR(16),direction VARCHAR(16),amount DECIMAL(18,6),balance_after DECIMAL(18,6),status VARCHAR(32),remark VARCHAR(255),created_at DATETIME,updated_at DATETIME,is_deleted TINYINT NOT NULL DEFAULT 0,UNIQUE KEY uk_ledger_biz_asset_direction(biz_no,asset,direction))");
        jdbc.execute("CREATE TABLE nx_earning_event (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_no VARCHAR(128),user_id BIGINT,user_device_id BIGINT,receipt_no VARCHAR(128),asset VARCHAR(16),amount DECIMAL(18,6),status VARCHAR(32),wallet_posted_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted TINYINT NOT NULL DEFAULT 0,UNIQUE KEY uk_earning_event_no(event_no))");
        jdbc.execute("CREATE TABLE nx_user_device_runtime (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_device_id BIGINT,online_status VARCHAR(32),paused_reason VARCHAR(64),active_task_no VARCHAR(96),updated_at DATETIME,is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_config_item (id BIGINT AUTO_INCREMENT PRIMARY KEY,config_key VARCHAR(128),config_value VARCHAR(128),status TINYINT,is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_order (id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(96),user_id BIGINT,payment_status VARCHAR(32),order_status VARCHAR(32),activation_status VARCHAR(32),is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE fixture_outbox (event_id VARCHAR(64) PRIMARY KEY,event_type VARCHAR(128) NOT NULL,payload TEXT NOT NULL)");
    }

    private void seedPendingRunningTask() {
        jdbc.update("INSERT INTO nx_user(id,sandbox,status,created_at,is_deleted) VALUES(91,0,'ACTIVE','2026-07-01 00:00:00',0)");
        jdbc.update("INSERT INTO nx_user_device(id,user_id,instance_no,name,gpu_model,product_code,device_type,status,row_version,vram_total_gb,purchased_at,activated_at,daily_usdt,pending_deactivate,source_environment,run_id,source_channel,ownership_status,is_deleted,updated_at) VALUES(8101,91,'DEV-8101','Device','GPU','stellarbox-pro','DEVICE','BUSY',7,12,'2026-08-01 00:00:00','2026-08-01 00:00:00',5,1,'PRODUCTION','','DEVELOPMENT_HOME','OWNED',0,'2026-08-27 12:00:00')");
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,task_config_id,task_name,model_name,reward_usdt,required_seconds,task_lock_minutes,completion_nonce,proof_expires_at,source_environment,client_name,status,started_at,worker_ack_at,lease_expires_at,attempt_count,max_attempts,created_at,updated_at,is_deleted) VALUES('DEV-TASK-ROLLBACK',91,8101,'EM','EM-8','Embedding','BGE-M3',0.05,5,0,'nonce','2026-08-28 12:00:00','PRODUCTION','NexGrid Development Workload','RUNNING','2026-08-27 11:59:54','2026-08-27 11:59:54','2026-08-28 12:00:00',1,3,'2026-08-27 11:59:54','2026-08-27 11:59:54',0)");
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,lifetime_earned,version,created_at,updated_at,is_deleted) VALUES(91,2,0,1,NOW(),NOW(),0)");
    }

    private static void requireLoopbackServerUrl(String url) {
        if (url == null || !LOOPBACK_SERVER_URL.matcher(url.trim()).matches()) {
            throw new IllegalArgumentException("NEXION_TEST_DB_SERVER_URL_MUST_TARGET_LOOPBACK_WITHOUT_CATALOG");
        }
    }

    private static String fixtureUrl(String serverUrl, String schema) {
        int query = serverUrl.indexOf('?');
        String base = query >= 0 ? serverUrl.substring(0, query) : serverUrl;
        String suffix = query >= 0 ? serverUrl.substring(query) : "";
        return base.substring(0, base.lastIndexOf('/') + 1) + schema + suffix;
    }

    /** Writes a minimal outbox row through the transaction-bound fixture datasource; it is not EventOutboxService itself. */
    private static final class TransactionalOutbox extends EventOutboxService {
        private final JdbcTemplate jdbc;

        private TransactionalOutbox(JdbcTemplate jdbc) {
            super(null, null, null, null);
            this.jdbc = jdbc;
        }

        @Override
        public String publishUserEvent(
                String aggregateType, String aggregateId, String eventType, Long userId,
                String phase, Integer accountAgeMonths, String cohort, Object payload) {
            String eventId = UUID.randomUUID().toString().replace("-", "");
            jdbc.update("INSERT INTO fixture_outbox(event_id,event_type,payload) VALUES(?,?,?)",
                    eventId, eventType, String.valueOf(payload));
            return eventId;
        }
    }
}
