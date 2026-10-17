package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real mapper and Spring service transaction; outbox failure is injected, not a real published event. */
class DeviceReactivationRuntimeMySqlTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void reactivationRestoresOnlyUserPauseAndRollsBackWithActivation() throws Exception {
        String url = System.getenv("NEXION_TEST_DB_SERVER_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/(\\?.*)?").doesNotContain(":3306/");
        String user = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        String schema = "nx_reactivate_runtime_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url, user, password); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + schema);
            System.out.println("REACTIVATE_FIXTURE_CREATE=" + schema);
            try {
                int q = url.indexOf('?');
                String fixtureUrl = url.substring(0, q < 0 ? url.length() : q) + schema + (q < 0 ? "" : url.substring(q));
                var source = new DriverManagerDataSource(fixtureUrl, user, password);
                var jdbc = new JdbcTemplate(source);
                jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(16),sandbox INT,is_deleted INT)");
                jdbc.execute("""
                    CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,instance_no VARCHAR(32),
                    status VARCHAR(24),ownership_status VARCHAR(24),device_type VARCHAR(24),row_version BIGINT,
                    activated_at DATETIME,deactivated_at DATETIME,pending_deactivate INT,updated_at DATETIME,
                    source_environment VARCHAR(24),run_id VARCHAR(32),is_deleted INT)
                    """);
                jdbc.execute("""
                    CREATE TABLE nx_user_device_runtime(user_device_id BIGINT PRIMARY KEY,online_status VARCHAR(24),
                    paused_reason VARCHAR(128),heartbeat_at DATETIME,active_task_no VARCHAR(64),updated_at DATETIME,is_deleted INT)
                    """);
                jdbc.update("INSERT INTO nx_user VALUES(42,'ACTIVE',0,0)");
                jdbc.update("INSERT INTO nx_user_device VALUES(9,42,'DEV-9','DEACTIVATED','OWNED','DEVICE',1,NULL,'2026-10-01',0,NULL,'PRODUCTION','',0)");
                jdbc.update("INSERT INTO nx_user_device_runtime VALUES(9,'OFFLINE','USER_DEACTIVATED','2026-09-30',NULL,NULL,0)");
                var config = new Configuration(new Environment("reactivation", new SpringManagedTransactionFactory(), source));
                config.setMapUnderscoreToCamelCase(true);
                config.addMapper(CanonicalStateMapper.class);
                config.addMapper(AppTaskAssignmentMapper.class);
                config.addMapper(DevelopmentHomeSettlementMapper.class);
                var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
                var realMapper = session.getMapper(CanonicalStateMapper.class);
                var mapper = mock(CanonicalStateMapper.class, org.mockito.AdditionalAnswers.delegatesTo(realMapper));
                doReturn(3).when(mapper).deviceSlotCap();
                doReturn(0).when(mapper).activeDeviceCount(42L);
                doReturn(new CanonicalStateMapper.UserEventAttribution("P1", 1, "2026-10")).when(mapper).userEventAttribution(42L);
                var idempotency = mock(AdminIdempotencyService.class);
                when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class),
                        org.mockito.ArgumentMatchers.<java.util.function.Supplier<ApiResult>>any()))
                        .thenAnswer(i -> ((java.util.function.Supplier<?>) i.getArgument(4)).get());
                var outbox = mock(EventOutboxService.class);
                var service = new AppCanonicalBoundaryService(mapper, null, idempotency, outbox, null, null,
                        mock(AuditLogService.class), null, null, null, null, null, null);
                var proxy = new ProxyFactory(service);
                proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
                var actual = (AppCanonicalBoundaryService) proxy.getProxy();
                // The real service writes CAS + runtime, then an outbox failure rolls both back.
                doThrow(new IllegalStateException("outbox failure")).when(outbox)
                        .publishUserEvent(anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
                assertThatThrownBy(() -> actual.activateDevice(42L, 9L, 1L, null, "rollback"))
                        .isInstanceOf(IllegalStateException.class).hasMessage("outbox failure");
                assertThat(jdbc.queryForMap("SELECT status,row_version FROM nx_user_device WHERE id=9"))
                        .containsEntry("status", "DEACTIVATED").containsEntry("row_version", 1L);
                assertThat(jdbc.queryForMap("SELECT online_status,paused_reason FROM nx_user_device_runtime WHERE user_device_id=9"))
                        .containsEntry("online_status", "OFFLINE").containsEntry("paused_reason", "USER_DEACTIVATED");
                reset(outbox);
                var telemetryBefore = jdbc.queryForMap("SELECT heartbeat_at,active_task_no FROM nx_user_device_runtime WHERE user_device_id=9");
                assertThat(actual.activateDevice(42L, 9L, 1L, null, "success").getCode()).isZero();
                assertThat(jdbc.queryForMap("SELECT online_status,paused_reason FROM nx_user_device_runtime WHERE user_device_id=9"))
                        .containsEntry("online_status", "ONLINE").containsEntry("paused_reason", null);
                assertThat(jdbc.queryForMap("SELECT heartbeat_at,active_task_no FROM nx_user_device_runtime WHERE user_device_id=9"))
                        .isEqualTo(telemetryBefore);
                // Same-state invocation cannot silently repair or clear a runtime pause.
                jdbc.update("UPDATE nx_user_device_runtime SET paused_reason='USER_DEACTIVATED',online_status='OFFLINE'");
                assertThat(actual.activateDevice(42L, 9L, 2L, null, "already-active").getCode()).isZero();
                assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime", String.class)).isEqualTo("USER_DEACTIVATED");
                // All three real stop writers preserve an existing administrative pause.
                var taskMapper = session.getMapper(AppTaskAssignmentMapper.class);
                var homeMapper = session.getMapper(DevelopmentHomeSettlementMapper.class);
                for (int stop = 0; stop < 3; stop++) {
                    jdbc.update("UPDATE nx_user_device_runtime SET paused_reason='ADMIN_REVIEW',online_status='ONLINE'");
                    if (stop == 0) realMapper.markDeviceRuntimeDeactivated(9L);
                    if (stop == 1) taskMapper.markRuntimeDeactivated(42L, 9L, LocalDateTime.now());
                    if (stop == 2) homeMapper.markDevelopmentDeviceRuntimeDeactivated(42L, 9L, LocalDateTime.now());
                    assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime", String.class)).isEqualTo("ADMIN_REVIEW");
                    jdbc.update("UPDATE nx_user_device SET status='DEACTIVATED',activated_at=NULL,deactivated_at=NOW(),row_version=1");
                    assertThat(actual.activateDevice(42L, 9L, 1L, null, "admin-" + stop).getCode()).isZero();
                    assertThat(jdbc.queryForMap("SELECT online_status,paused_reason FROM nx_user_device_runtime"))
                            .containsEntry("online_status", "OFFLINE").containsEntry("paused_reason", "ADMIN_REVIEW");
                }
                for (String reason : new String[]{null, "", "ADMIN_PAUSED", "DC_PAUSED", "user_deactivated", "USER_DEACTIVATED "}) {
                    jdbc.update("UPDATE nx_user_device_runtime SET paused_reason=?,online_status='OFFLINE'", reason);
                    assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).as("preserve %s", reason).isZero();
                }
                jdbc.update("UPDATE nx_user_device_runtime SET paused_reason='USER_DEACTIVATED'");
                assertThat(realMapper.resumeUserDeactivatedRuntime(99L, 9L)).isZero();
                for (String deviceType : new String[]{"PHONE", "MOBILE"}) {
                    jdbc.update("UPDATE nx_user_device SET device_type=?", deviceType);
                    assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).isZero();
                }
                jdbc.update("UPDATE nx_user_device SET device_type='DEVICE'");
                jdbc.update("UPDATE nx_user SET sandbox=1");
                assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).isZero();
                jdbc.update("UPDATE nx_user SET sandbox=0");
                jdbc.update("UPDATE nx_user_device SET ownership_status='TRANSFERRED'");
                assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).isZero();
                jdbc.update("UPDATE nx_user_device SET ownership_status='OWNED',is_deleted=1");
                assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).isZero();
                jdbc.update("UPDATE nx_user_device SET is_deleted=0");
                jdbc.update("UPDATE nx_user SET is_deleted=1");
                assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).isZero();
                jdbc.update("UPDATE nx_user SET is_deleted=0");
                jdbc.update("UPDATE nx_user_device_runtime SET is_deleted=1");
                assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).isZero();
                jdbc.update("DELETE FROM nx_user_device_runtime");
                assertThat(realMapper.resumeUserDeactivatedRuntime(42L, 9L)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime", Integer.class)).isZero();
            } finally {
                statement.execute("DROP DATABASE " + schema);
                System.out.println("REACTIVATE_FIXTURE_DROP=" + schema);
            }
        }
    }
}
