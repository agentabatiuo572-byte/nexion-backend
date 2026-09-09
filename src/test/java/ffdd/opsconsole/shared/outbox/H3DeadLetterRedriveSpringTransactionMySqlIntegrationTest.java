package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.dto.H3OutboxRedriveRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.UUID;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Opt-in transaction proof using only a random, strictly owned fixture schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class H3DeadLetterRedriveSpringTransactionMySqlIntegrationTest {
    private static final Pattern OWNED_DATABASE = Pattern.compile("nx_h3_redrive_test_[0-9a-f]{32}");
    private static final Pattern LOOPBACK_SERVER_URL = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private Connection admin;
    private DataSource fixtureDataSource;
    private JdbcTemplate jdbc;
    private String database;
    private boolean created;
    private String eventId;
    private String idempotencyKey;

    @BeforeEach
    void createOwnedFixtureSchema() throws Exception {
        String sourceUrl = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        requireServerUrlWithoutCatalog(sourceUrl);
        String user = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        admin = DriverManager.getConnection(sourceUrl, user, password);
        assertThat(adminDatabase()).isNull();
        database = "nx_h3_redrive_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(OWNED_DATABASE.matcher(database).matches()).isTrue();
        try {
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            created = true;
            fixtureDataSource = new DriverManagerDataSource(fixtureUrl(sourceUrl, database), user, password);
            jdbc = new JdbcTemplate(fixtureDataSource);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
            createFixtureTables();
            eventId = "H3-REDRIVE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            idempotencyKey = "h3-redrive-it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            jdbc.update("""
                    INSERT INTO nx_event_outbox (
                      event_id,aggregate_type,aggregate_id,event_type,event_name,family_key,event_ts,phase,
                      account_age_months,cohort,is_server_authoritative,schema_registered,analytics_event,payload,
                      status,retry_count,last_error,is_deleted
                    ) VALUES (?,?,?,'H3_STOREFRONT_THREE_PRODUCTS_VIEWED',?, ?, NOW(3),'P2',1,'2026-W37',1,0,0,
                      JSON_OBJECT(),'DEAD',5,'A4_SCHEMA_PROPERTY_NOT_REGISTERED',0)
                    """, eventId, "H3_WEEKLY_PARTICIPATION", eventId,
                    "internal.h3_storefront_three_products_viewed", "internal");
            jdbc.update("""
                    INSERT INTO nx_event_consumer_delivery (
                      event_id,consumer_group,topic,msg_id,event_type,aggregate_type,aggregate_id,
                      status,attempt_count,rocketmq_reconsume_times,dead_at,last_error,
                      first_seen_at,last_seen_at,created_at,updated_at,is_deleted
                    ) VALUES (?,'h3-quest-completion','h3.quest.completed','h3-redrive-test',
                      'H3_STOREFRONT_THREE_PRODUCTS_VIEWED','H3_WEEKLY_PARTICIPATION',?,'DEAD',5,0,NOW(),
                      'A4_SCHEMA_PROPERTY_NOT_REGISTERED',NOW(),NOW(),NOW(),NOW(),0)
                    """, eventId, eventId);
        } catch (Exception failure) {
            discardOwnedFixtureSchema();
            throw failure;
        }
    }

    @AfterEach
    void discardFixture() throws Exception {
        try {
            discardOwnedFixtureSchema();
        } finally {
            if (admin != null) admin.close();
        }
    }

    @Test
    void requiredAuditFailureRollsBackTheCasInTheOwnedSchemaAndMarksIdempotencyFailed() {
        AuditLogService audit = org.mockito.Mockito.mock(AuditLogService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("H3_TEST_AUDIT_UNAVAILABLE"))
                .when(audit).recordRequired(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service(audit).redrive(eventId, idempotencyKey,
                new H3OutboxRedriveRequest("验证审计失败时重投状态必须事务回滚", 5, "DEAD", 5)))
                .isInstanceOf(IllegalStateException.class).hasMessage("H3_TEST_AUDIT_UNAVAILABLE");

        assertThat(jdbc.queryForObject("SELECT status FROM nx_event_outbox WHERE event_id=?", String.class, eventId))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM nx_event_outbox WHERE event_id=?", Integer.class, eventId))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_event_consumer_delivery WHERE event_id=? AND consumer_group='h3-quest-completion'",
                String.class, eventId)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM nx_event_consumer_delivery WHERE event_id=? AND consumer_group='h3-quest-completion'",
                Integer.class, eventId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT last_error FROM nx_event_consumer_delivery WHERE event_id=? AND consumer_group='h3-quest-completion'",
                String.class, eventId)).isEqualTo("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",
                String.class, "A4_H3_OUTBOX_REDRIVE:" + eventId, idempotencyKey)).isEqualTo("FAILED");
    }

    private void createFixtureTables() {
        jdbc.execute("""
                CREATE TABLE nx_event_outbox (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(64) NOT NULL,aggregate_type VARCHAR(64) NOT NULL,
                  aggregate_id VARCHAR(128) NOT NULL,event_type VARCHAR(96) NOT NULL,event_name VARCHAR(128),family_key VARCHAR(32),
                  event_ts DATETIME(3),phase VARCHAR(32),account_age_months INT NOT NULL DEFAULT 0,cohort VARCHAR(16),
                  is_server_authoritative TINYINT NOT NULL DEFAULT 0,schema_revision INT,schema_registered TINYINT NOT NULL DEFAULT 0,
                  analytics_event TINYINT NOT NULL DEFAULT 0,payload JSON NOT NULL,status VARCHAR(32) NOT NULL,retry_count INT NOT NULL DEFAULT 0,
                  next_retry_at DATETIME,published_at DATETIME,last_error VARCHAR(512),created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,is_deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_event_outbox_event_id (event_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_event_consumer_delivery (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(64) NOT NULL,consumer_group VARCHAR(128) NOT NULL,
                  topic VARCHAR(255),msg_id VARCHAR(128),event_type VARCHAR(128),aggregate_type VARCHAR(64),aggregate_id VARCHAR(128),
                  status VARCHAR(32) NOT NULL,attempt_count INT NOT NULL DEFAULT 0,rocketmq_reconsume_times INT NOT NULL DEFAULT 0,
                  next_retry_at DATETIME,processed_at DATETIME,dead_at DATETIME,created_commissions INT,last_error VARCHAR(512),
                  first_seen_at DATETIME, last_seen_at DATETIME,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,is_deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_event_consumer_delivery_group_event (consumer_group,event_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_admin_idempotency_record (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,scope VARCHAR(96) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,
                  request_hash CHAR(64) NOT NULL,status VARCHAR(32) NOT NULL,response_json JSON,error_message VARCHAR(512),
                  expires_at DATETIME NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,is_deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_admin_idem_scope_key (scope,idempotency_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private H3DeadLetterRedriveService service(AuditLogService audit) {
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(fixtureDataSource));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(fixtureDataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource());
        AdminIdempotencyRecordMapper records = template.getMapper(AdminIdempotencyRecordMapper.class);
        AdminIdempotencyExpiryTransitionExecutor transition = proxied(
                new AdminIdempotencyExpiryTransitionExecutor(records), interceptor,
                AdminIdempotencyExpiryTransitionExecutor.class);
        AdminIdempotencyTransactionExecutor executor = proxied(new AdminIdempotencyTransactionExecutor(
                records, new ObjectMapper().findAndRegisterModules(), transition), interceptor,
                AdminIdempotencyTransactionExecutor.class);
        return new H3DeadLetterRedriveService(template.getMapper(EventOutboxMapper.class),
                template.getMapper(EventConsumerDeliveryMapper.class),
                new AdminIdempotencyService(executor, Clock.systemUTC()),
                org.mockito.Mockito.mock(A2RuntimePolicy.class), audit);
    }

    private SqlSessionFactory sessionFactory(DataSource source) {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "h3-redrive-transaction", new SpringManagedTransactionFactory(), source));
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        globalConfig.setMetaObjectHandler(new ffdd.opsconsole.shared.config.MybatisMetaObjectHandler(Clock.systemUTC()));
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EventOutboxMapper.class);
        configuration.addMapper(EventConsumerDeliveryMapper.class);
        configuration.addMapper(AdminIdempotencyRecordMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private <T> T proxied(T target, TransactionInterceptor interceptor, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }

    private void discardOwnedFixtureSchema() throws Exception {
        if (created && database != null && OWNED_DATABASE.matcher(database).matches()) {
            try (var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            } finally {
                created = false;
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

    private String adminDatabase() throws Exception {
        try (var statement = admin.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next()) throw new IllegalStateException("H3_FIXTURE_DATABASE_SCOPE_UNAVAILABLE");
            return result.getString(1);
        }
    }
}
