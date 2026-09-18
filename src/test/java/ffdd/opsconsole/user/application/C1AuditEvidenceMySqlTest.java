package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.user.domain.*;
import ffdd.opsconsole.user.mapper.C1AuditEvidenceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Opt-in, disposable schema on this task's isolated MySQL only; never uses application DB defaults. */
@EnabledIfEnvironmentVariable(named = "NEXION_C1_AUDIT_MYSQL", matches = "true")
class C1AuditEvidenceMySqlTest {
    private static final String BASE = "jdbc:mysql://127.0.0.1:18362/";
    private static final String SCHEMA = "bug4_c1_" + UUID.randomUUID().toString().replace("-", "");
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory factory;

    @BeforeAll static void createDisposableSchema() {
        JdbcTemplate server = new JdbcTemplate(source(""));
        server.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        source = source(SCHEMA);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE nx_event_outbox (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(64), aggregate_type VARCHAR(64), aggregate_id VARCHAR(96),
                event_type VARCHAR(96), event_name VARCHAR(96), family_key VARCHAR(64), event_ts DATETIME,
                phase VARCHAR(32), account_age_months INT, cohort VARCHAR(32), is_server_authoritative BOOLEAN,
                schema_revision INT, schema_registered BOOLEAN, analytics_event BOOLEAN, payload JSON,
                status VARCHAR(32) DEFAULT 'PENDING', retry_count INT DEFAULT 0, next_retry_at DATETIME,
                published_at DATETIME, last_error VARCHAR(512), created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME, is_deleted INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE nx_audit_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, biz_no VARCHAR(96), action VARCHAR(96), resource_type VARCHAR(96),
                resource_id VARCHAR(96), user_id BIGINT, actor_username VARCHAR(96), actor_type VARCHAR(32) DEFAULT 'ADMIN',
                result VARCHAR(32) DEFAULT 'SUCCESS', detail_json JSON, is_deleted INT DEFAULT 0,
                INDEX idx_audit_biz_no(biz_no))
                """);
        Configuration configuration = new Configuration(new Environment("bug4-isolated", new JdbcTransactionFactory(), source));
        configuration.addMapper(EventOutboxMapper.class);
        configuration.addMapper(PlatformConfigItemMapper.class);
        configuration.addMapper(C1AuditEvidenceMapper.class);
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    private static DriverManagerDataSource source(String schema) {
        return new DriverManagerDataSource(BASE + schema + "?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "root", System.getenv("NEXION_C1_AUDIT_MYSQL_PASSWORD"));
    }

    @AfterAll static void dropOnlyOwnedSchema() {
        if (SCHEMA.matches("bug4_c1_[a-f0-9]{32}")) new JdbcTemplate(source("")).execute("DROP DATABASE IF EXISTS " + SCHEMA);
    }

    @BeforeEach void clearOwnedFixture() {
        jdbc.update("DELETE FROM nx_event_outbox");
        jdbc.update("DELETE FROM nx_audit_log");
    }

    @Test void unlinkedHeadCannotStarveLinkedProfileAndRemainsVisibleWithoutRetries() {
        for (int i = 0; i < 150; i++) insertEvent("old-" + i, "ADMIN_USER_PROFILE_VIEWED");
        insertEvent("new-151", "ADMIN_USER_PROFILE_VIEWED");
        insertEvent("other", "UNRELATED");
        insertProfileAudit("C1-VIEW-new-151", "operator");
        try (SqlSession session = factory.openSession(true)) {
            var outbox = session.getMapper(EventOutboxMapper.class);
            assertThat(outbox.listPendingByEventType("ADMIN_USER_PROFILE_VIEWED", 100))
                    .extracting(EventOutboxMessage::getEventId).containsExactly("new-151");
            assertThat(outbox.listPendingByEventType("UNRELATED", 100))
                    .extracting(EventOutboxMessage::getEventId).containsExactly("other");
            var metric = session.getMapper(PlatformConfigItemMapper.class).selectA3EventBacklog();
            assertThat(((Number) metric.get("backlog")).longValue()).isEqualTo(152);
            assertThat(((Number) metric.get("audit_link_unresolved")).longValue()).isEqualTo(150);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE status='PENDING' AND retry_count=0", Long.class))
                .isEqualTo(152L);
    }

    @Test void exactProfileIdentityAndExportAuditFieldsAreRequired() {
        insertProfileAudit("C1-VIEW-event", "operator");
        jdbc.update("INSERT INTO nx_audit_log(action,resource_type,resource_id,actor_username,detail_json) VALUES (?,?,?,?,?)",
                "ADMIN.USER_LIST_EXPORTED", "USER_PROFILE_EXPORT", "job-1", "operator",
                "{\"filterHash\":\"" + "a".repeat(64) + "\",\"rowCount\":3,\"masked\":true}");
        try (SqlSession session = factory.openSession(true)) {
            var mapper = session.getMapper(C1AuditEvidenceMapper.class);
            assertThat(mapper.countProfileEvidence("event", "U52", 52L, "operator", "SUPPORT", "[\"profile\"]")).isEqualTo(1);
            assertThat(mapper.countProfileEvidence("event", "U52", 53L, "operator", "SUPPORT", "[\"profile\"]")).isZero();
            assertThat(mapper.countProfileEvidence("event", "U52", 52L, "Operator", "SUPPORT", "[\"profile\"]")).isZero();
            assertThat(mapper.countProfileEvidence("different", "U52", 52L, "operator", "SUPPORT", "[\"profile\"]")).isZero();
            assertThat(mapper.countProfileEvidence("event", "U52", 52L, "operator", "SUPER_ADMIN", "[\"profile\"]")).isZero();
            assertThat(mapper.countProfileEvidence("event", "U52", 52L, "operator", "SUPPORT", "[\"profile\",\"assets\"]")).isZero();
            assertThat(mapper.countExportEvidence("job-1", "operator", "a".repeat(64), 3)).isEqualTo(1);
            assertThat(mapper.countExportEvidence("job-1", "operator", "a".repeat(64), 4)).isZero();
            assertThat(mapper.countExportEvidence("job-2", "operator", "a".repeat(64), 3)).isZero();
            assertThat(mapper.countExportEvidence("job-1", "wrong", "a".repeat(64), 3)).isZero();
            jdbc.update("UPDATE nx_audit_log SET result='FAILED'");
            session.clearCache();
            assertThat(mapper.countProfileEvidence("event", "U52", 52L, "operator", "SUPPORT", "[\"profile\"]")).isZero();
            assertThat(mapper.countExportEvidence("job-1", "operator", "a".repeat(64), 3)).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"admin_user_list_exported", "\u00c1DMIN_USER_LIST_EXPORTED"})
    void caseAndAccentInsensitiveDatabaseSelectionCannotTurnMalformedC1TypeIntoSuccess(String storedType) {
        insertEvent("case-alias", storedType);
        try (SqlSession session = factory.openSession(true)) {
            var selected = session.getMapper(EventOutboxMapper.class).listPendingByEventType("ADMIN_USER_LIST_EXPORTED", 100);
            assertThat(selected).hasSize(1);
            var service = mock(EventOutboxService.class);
            var publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
            when(service.listPendingByEventType("ADMIN_USER_LIST_EXPORTED", 100)).thenReturn(selected);
            new EventOutboxDispatchScheduler(service, publisher).dispatchPending();
            verify(service).markFailed("case-alias", "C1_AUDIT_ENVELOPE_INVALID");
            verifyNoInteractions(publisher);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void requiredAuditFailureRollsBackOutboxFromBothProfileEntrypoints(boolean stringEntry) {
        OpsUserService users = mock(OpsUserService.class);
        UserOpsRepository repository = mock(UserOpsRepository.class);
        AdminOperatorRoleResolver roles = mock(AdminOperatorRoleResolver.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(roles.resolveCode()).thenReturn("SUPPORT");
        when(repository.findUserIdByLookupKey("U52")).thenReturn(Optional.of(52L));
        when(users.profile(52L)).thenReturn(ApiResult.ok(new UserAccountView(52L, "U52", "fixture", "masked", "86",
                "ACTIVE", "L2", "V1", true, BigDecimal.ZERO, BigDecimal.ZERO, 0, "low", 0L, 0L,
                LocalDateTime.now(), LocalDateTime.now())));
        when(outbox.publish(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            insertEvent("transaction-event", "ADMIN_USER_PROFILE_VIEWED");
            return "transaction-event";
        });
        doAnswer(call -> {
            AuditLogWriteRequest request = call.getArgument(0);
            assertThat(request.getBizNo()).isEqualTo("C1-VIEW-transaction-event");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Long.class)).isEqualTo(1L);
            insertProfileAudit(request.getBizNo(), "operator");
            throw new IllegalStateException("AUDIT_REQUIRED_TEST_FAILURE");
        }).when(audit).recordRequired(any());
        var target = new OpsUser360Service(users, mock(OpsFinanceService.class), mock(OpsTreasuryService.class),
                mock(OpsDeviceService.class), mock(OpsRiskService.class), audit, repository, roles, outbox);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        OpsUser360Service proxy = (OpsUser360Service) proxyFactory.getProxy();
        assertThatThrownBy(() -> { if (stringEntry) proxy.detail("U52"); else proxy.detail(52L); })
                .hasMessage("AUDIT_REQUIRED_TEST_FAILURE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log", Long.class)).isZero();
        verify(audit).recordRequired(any());
    }

    private void insertEvent(String id, String type) {
        jdbc.update("INSERT INTO nx_event_outbox(event_id,event_type) VALUES (?,?)", id, type);
    }

    private void insertProfileAudit(String bizNo, String actor) {
        jdbc.update("INSERT INTO nx_audit_log(biz_no,action,resource_type,resource_id,user_id,actor_username,detail_json) VALUES (?,?,?,?,?,?,?)",
                bizNo, "ADMIN.USER_PROFILE_VIEWED", "USER_PROFILE", "U52", 52L, actor, "{\"role\":\"SUPPORT\",\"cardsViewed\":[\"profile\"]}");
    }
}
