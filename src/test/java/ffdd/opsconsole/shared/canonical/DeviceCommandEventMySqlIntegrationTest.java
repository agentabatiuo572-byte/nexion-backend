package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.api.ApiResultHttpStatusAdvice;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.OutboxProperties;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Real MySQL + HTTP transaction regression for the A4 device-command contracts.
 *
 * <p>Only the idempotency adapter and non-core collaborators are mocked; audit persistence is not
 * asserted here. The canonical boundary, MyBatis SQL, A4 gate and outbox execute against an owned,
 * empty database so repeated mapper references remain real MySQL joins rather than temporary-table
 * aliases.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class DeviceCommandEventMySqlIntegrationTest {
    private static final String MIGRATION = "20260831_app_device_command_event_schema.sql";
    private static final long USER_ID = 420031L;
    private static final long OTHER_USER_ID = 420032L;
    private static final long DEVICE_ID = 930031L;
    private static final long OTHER_DEVICE_ID = 930032L;
    private static final String ACTIVATED = "device.activated";
    private static final String DEACTIVATED = "device.deactivated";
    private static final Pattern OWNED_DATABASE = Pattern.compile("nx_device_event_test_[0-9a-f]{32}");
    private static final List<String> FIXTURE_TABLES = List.of(
            "nx_user", "nx_config_item", "nx_user_device", "nx_user_device_runtime", "nx_compute_task",
            "nx_compute_receipt", "nx_compute_dc_ops_state", "nx_event_schema_revision", "nx_event_schema_registry",
            "nx_event_schema_property", "nx_admin_event_lifecycle", "nx_event_outbox");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AuditLogService audit = mock(AuditLogService.class);
    private Connection connection;
    private JdbcTemplate jdbc;
    private MockMvc http;
    private EventOutboxService outbox;
    private String fixtureDatabase;
    private boolean fixtureDatabaseCreated;

    @BeforeEach
    void useOwnedEmptyDatabaseAndRealTransactionalBoundary() throws Exception {
        connection = DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        fixtureDatabase = "nx_device_event_test_" + UUID.randomUUID().toString().replace("-", "");
        assertOwnedFixtureDatabase();
        List<String> ddl = FIXTURE_TABLES.stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE `" + table + "`", (rs, row) -> rs.getString(2)))
                .toList();
        try {
            jdbc.execute("CREATE DATABASE `" + fixtureDatabase + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            fixtureDatabaseCreated = true;
            jdbc.execute("USE `" + fixtureDatabase + "`");
            for (String tableDdl : ddl) jdbc.execute(tableDdl);
        } catch (Exception failure) {
            dropOwnedFixtureDatabase();
            throw failure;
        }
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);

        Configuration configuration = new Configuration(new Environment("device-command-mysql",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CanonicalStateMapper.class);
        configuration.addMapper(EventOutboxMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(
                new MybatisSqlSessionFactoryBuilder().build(configuration));

        outbox = new EventOutboxService(session.getMapper(EventOutboxMapper.class), json,
                new OutboxProperties(), mock(A4RuntimePolicyService.class));
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        // Deliberately only bypasses the persistence/replay adapter: A4 runs inside the supplied action.
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(4)).get());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        AppCanonicalBoundaryService target = new AppCanonicalBoundaryService(
                session.getMapper(CanonicalStateMapper.class), mock(TamperDetectionPublisher.class), idempotency, outbox,
                mock(AppGrowthLifecyclePublisher.class), mock(GrowthRhythmFacade.class), audit,
                mock(CommerceAcceptanceSandboxMapper.class), mock(FundsSandboxProfileGuard.class),
                new CommerceAcceptanceRun("device-command-test-0001"), mock(StorefrontProductReleasePolicy.class),
                new StorefrontPurchaseGatePolicy(), environment);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        http = MockMvcBuilders.standaloneSetup(new AppCanonicalBoundaryController(
                        (AppCanonicalBoundaryService) proxy.getProxy(), null, null, null, null))
                .setControllerAdvice(new GlobalExceptionHandler(audit), new ApiResultHttpStatusAdvice()).build();
        seedUser(USER_ID, "420031");
        seedUser(OTHER_USER_ID, "420032");
    }

    @AfterEach
    void discardOwnedFixtureDatabase() throws Exception {
        try {
            dropOwnedFixtureDatabase();
        } finally {
            if (connection != null) connection.close();
        }
    }

    @Test
    void concurrentForceCancellationWaitsForCompletionLocksAndPreservesCompletedTask() throws Exception {
        migrate();
        seedActiveDeviceWithTasks();
        // Use the real completion mapper lock sequence, but no settlement/payout fixture.
        // The service unit test separately enforces that completion uses this same order.
        try (Connection completion = DriverManager.getConnection(connection.getMetaData().getURL(),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"))) {
            completion.setCatalog(fixtureDatabase);
            JdbcTemplate completionJdbc = new JdbcTemplate(new SingleConnectionDataSource(completion, true));
            assertThat(completionJdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureDatabase);
            completion.setAutoCommit(false);
            Configuration configuration = new Configuration(new Environment("device-completion-locks",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                    new SingleConnectionDataSource(completion, true)));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(AppTaskAssignmentMapper.class);
            var executor = Executors.newSingleThreadExecutor();
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(completion)) {
                AppTaskAssignmentMapper tasks = session.getMapper(AppTaskAssignmentMapper.class);
                assertThat(tasks.lockProductionUser(USER_ID)).isEqualTo(USER_ID);
                assertThat(tasks.assignmentDeviceId(USER_ID, "TASK-CLAIMED", "PRODUCTION")).isEqualTo(DEVICE_ID);
                assertThat(tasks.lockOwnedDevice(USER_ID, DEVICE_ID)).isNotNull();
                assertThat(tasks.lockAssignment(USER_ID, "TASK-CLAIMED", "PRODUCTION").status()).isEqualTo("CLAIMED");
                CountDownLatch cancellationEntered = new CountDownLatch(1);
                var cancellation = executor.submit(() -> {
                    cancellationEntered.countDown();
                    return deactivate(DEVICE_ID, 7L, "concurrent-force");
                });
                assertThat(cancellationEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> cancellation.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                completionJdbc.update("UPDATE nx_compute_task SET status='COMPLETED', completed_at=NOW(),"
                        + "proof_consumed_at=NOW() WHERE task_no='TASK-CLAIMED'");
                completion.commit();
                cancellation.get(10, TimeUnit.SECONDS).andExpect(status().isOk());
            } finally {
                // Release locks even on an assertion failure, before awaiting the other connection.
                if (!completion.isClosed()) completion.rollback();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertDevice("DEACTIVATED", 8L, 0);
        assertRuntime("OFFLINE", null);
        assertTask("TASK-CLAIMED", "COMPLETED", true);
        assertOutbox(DEACTIVATED, 1, "ACTIVE", "DEACTIVATED", 8L);
    }

    @Test
    void missingSchemaTwiceReturns422AndRollsBackDeviceRuntimeTaskAndOutbox() throws Exception {
        seedActiveDeviceWithTasks();

        for (int retry = 0; retry < 2; retry++) {
            deactivate(DEVICE_ID, 7L, "missing-schema-" + retry).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.message").value("A4_SCHEMA_NOT_REGISTERED"));
            assertDevice("ACTIVE", 7L, 0);
            assertRuntime("ONLINE", "TASK-CLAIMED");
            assertTask("TASK-CLAIMED", "CLAIMED", false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
        }
    }

    @Test
    void migrationReplaysAndActivatesThenForceDeactivatesWithOneCanonicalEventEach() throws Exception {
        seedInactiveDevice();
        migrate();
        migrate();

        activate(0L, "activate-once").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceNo").value("DEV-EVENT-930031"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.rowVersion").value(1));
        assertDevice("ACTIVE", 1L, 0);
        assertOutbox(ACTIVATED, 1, "DEACTIVATED", "ACTIVE", 1L);

        seedActiveTask("TASK-CLAIMED", DEVICE_ID, "PRODUCTION", "CLAIMED");
        seedOtherProductionDevice();
        seedActiveTask("TASK-OTHER-DEVICE", OTHER_DEVICE_ID, "PRODUCTION", "RUNNING");
        seedActiveTask("TASK-SANDBOX", DEVICE_ID, "SANDBOX", "RUNNING");
        seedCompletedHistoricalTaskWithReceipt("TASK-COMPLETED");
        seedRuntime("ONLINE", "TASK-CLAIMED");

        deactivate(DEVICE_ID, 1L, "deactivate-once").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceNo").value("DEV-EVENT-930031"))
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.data.rowVersion").value(2));
        assertDevice("DEACTIVATED", 2L, 0);
        assertRuntime("OFFLINE", null);
        assertTask("TASK-CLAIMED", "CANCELLED", true);
        assertTask("TASK-OTHER-DEVICE", "RUNNING", null);
        assertTask("TASK-SANDBOX", "RUNNING", null);
        assertTask("TASK-COMPLETED", "COMPLETED", false);
        assertThat(jdbc.queryForObject("SELECT reward_usdt FROM nx_compute_task WHERE task_no='TASK-COMPLETED'",
                java.math.BigDecimal.class)).isEqualByComparingTo("17.500000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_compute_receipt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reward_usdt FROM nx_compute_receipt WHERE task_no='TASK-COMPLETED'",
                java.math.BigDecimal.class)).isEqualByComparingTo("17.500000");
        assertOutbox(DEACTIVATED, 2, "ACTIVE", "DEACTIVATED", 2L);

        activate(2L, "reactivate-after-force").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.rowVersion").value(3));
        assertDevice("ACTIVE", 3L, 0);
        assertTask("TASK-CLAIMED", "CANCELLED", true);
        assertTask("TASK-COMPLETED", "COMPLETED", false);
        assertOutbox(ACTIVATED, 3, "DEACTIVATED", "ACTIVE", 3L);
    }

    @Test
    void exactRegisteredPayloadStillRejectsMissingWrongTypedAndExtraFields() throws Exception {
        migrate();
        for (String event : List.of(ACTIVATED, DEACTIVATED)) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_schema_property p JOIN nx_event_schema_registry s"
                    + " ON s.id=p.schema_id WHERE s.event_name=? AND p.is_deleted=0", Integer.class, event)).isEqualTo(5);
            assertThatThrownBy(() -> outbox.publishUserEvent("USER_DEVICE", "DEV-EVENT-930031", event, USER_ID,
                    "P1", 0, "2026-W01", Map.of("deviceId", DEVICE_ID, "instanceNo", "DEV-EVENT-930031",
                            "previousStatus", "ACTIVE", "status", "DEACTIVATED")))
                    .hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
            assertThatThrownBy(() -> outbox.publishUserEvent("USER_DEVICE", "DEV-EVENT-930031", event, USER_ID,
                    "P1", 0, "2026-W01", Map.of("deviceId", DEVICE_ID, "instanceNo", "DEV-EVENT-930031",
                            "previousStatus", "ACTIVE", "status", "DEACTIVATED", "rowVersion", "two")))
                    .hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
            assertThatThrownBy(() -> outbox.publishUserEvent("USER_DEVICE", "DEV-EVENT-930031", event, USER_ID,
                    "P1", 0, "2026-W01", Map.of("deviceId", DEVICE_ID, "instanceNo", "DEV-EVENT-930031",
                            "previousStatus", "ACTIVE", "status", "DEACTIVATED", "rowVersion", 2,
                            "unregisteredField", "must-fail")))
                    .hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_schema_property WHERE property_name='user_id'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    @Test
    void migrationDoesNotOverrideDisabledRetiredDeletedOrFutureOperatorState() throws Exception {
        migrate();
        jdbc.update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='disabled',version=9,changed_by='operator'"
                + " WHERE event_name=?", ACTIVATED);
        jdbc.update("UPDATE nx_event_schema_registry SET status='RETIRED',reason='operator-retired' WHERE event_name=?",
                DEACTIVATED);
        migrate();
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM nx_admin_event_lifecycle WHERE event_name=?",
                String.class, ACTIVATED)).isEqualTo("disabled");
        assertThat(jdbc.queryForObject("SELECT version FROM nx_admin_event_lifecycle WHERE event_name=?", Long.class,
                ACTIVATED)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_event_schema_registry WHERE event_name=?", String.class,
                DEACTIVATED)).isEqualTo("RETIRED");

        jdbc.update("UPDATE nx_event_schema_registry SET is_deleted=1 WHERE event_name=?", ACTIVATED);
        jdbc.update("UPDATE nx_event_schema_registry SET current_revision=316,reason='future-contract' WHERE event_name=?",
                DEACTIVATED);
        jdbc.update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id"
                + " SET p.registry_revision=316 WHERE s.event_name=?", DEACTIVATED);
        migrate();
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM nx_event_schema_registry WHERE event_name=?", Integer.class,
                ACTIVATED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_revision FROM nx_event_schema_registry WHERE event_name=?",
                Integer.class, DEACTIVATED)).isEqualTo(316);
        assertThat(jdbc.queryForObject("SELECT reason FROM nx_event_schema_registry WHERE event_name=?", String.class,
                DEACTIVATED)).isEqualTo("future-contract");
    }

    @Test
    void softDeletedRegisteredPropertyBlocksTheActualActivationRequestWithoutStateChange() throws Exception {
        migrate();
        seedInactiveDevice();
        jdbc.update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id"
                + " SET p.is_deleted=1 WHERE s.event_name=? AND p.property_name='status'", ACTIVATED);

        activate(0L, "soft-deleted-property").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("A4_SCHEMA_PROPERTY_NOT_REGISTERED"));

        assertDevice("DEACTIVATED", 0L, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    @Test
    void disabledLifecycleBlocksActualForceDeactivationAndRollsBackTaskRuntimeAndDevice() throws Exception {
        migrate();
        seedActiveDeviceWithTasks();
        jdbc.update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='disabled',version=11 WHERE event_name=?",
                DEACTIVATED);

        deactivate(DEVICE_ID, 7L, "disabled-lifecycle").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("A4_EVENT_LIFECYCLE_BLOCKED_DISABLED"));

        assertDevice("ACTIVE", 7L, 0);
        assertRuntime("ONLINE", "TASK-CLAIMED");
        assertTask("TASK-CLAIMED", "CLAIMED", false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    @Test
    void foreignAndStaleHttpCommandsDoNotChangeProductionDeviceOrPublish() throws Exception {
        migrate();
        seedInactiveDevice();
        jdbc.update("UPDATE nx_user_device SET user_id=? WHERE id=?", OTHER_USER_ID, DEVICE_ID);
        activate(0L, "foreign-device").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("DEVICE_FORBIDDEN"));
        assertDevice("DEACTIVATED", 0L, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();

        jdbc.update("UPDATE nx_user_device SET user_id=? WHERE id=?", USER_ID, DEVICE_ID);
        activate(9L, "stale-device").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("DEVICE_VERSION_CONFLICT"));
        assertDevice("DEACTIVATED", 0L, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    @Test
    void deactivateAfterTaskReturnsInstanceAndPendingReceiptAcrossAllBranches() throws Exception {
        migrate();
        seedDevice("ACTIVE", 7L);
        seedRuntime("ONLINE", null);

        deactivateAfterTask(7L, "after-task-no-active").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceNo").value("DEV-EVENT-930031"))
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.data.alreadyDeactivated").value(false));

        deactivateAfterTask(8L, "after-task-deactivated-replay").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceNo").value("DEV-EVENT-930031"))
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.data.alreadyDeactivated").value(true));

        jdbc.update("UPDATE nx_user_device SET status='ACTIVE',deactivated_at=NULL,pending_deactivate=0,row_version=12"
                + " WHERE id=?", DEVICE_ID);
        seedActiveTask("TASK-AFTER-TASK", DEVICE_ID, "PRODUCTION", "RUNNING");
        deactivateAfterTask(12L, "after-task-pending").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceNo").value("DEV-EVENT-930031"))
                .andExpect(jsonPath("$.data.status").value("PENDING_DEACTIVATE"))
                .andExpect(jsonPath("$.data.alreadyPending").value(false));
        deactivateAfterTask(12L, "after-task-pending-replay").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceNo").value("DEV-EVENT-930031"))
                .andExpect(jsonPath("$.data.status").value("PENDING_DEACTIVATE"))
                .andExpect(jsonPath("$.data.alreadyPending").value(true));
    }

    private void migrate() throws Exception {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/" + MIGRATION));
    }

    private ResultActions activate(long expectedVersion, String key) throws Exception {
        return http.perform(post("/api/devices/activate").contentType("application/json")
                .content("{\"deviceId\":" + DEVICE_ID + ",\"expectedVersion\":" + expectedVersion + "}")
                .header("Idempotency-Key", key).principal(authentication(USER_ID)));
    }

    private ResultActions deactivate(long deviceId, long expectedVersion, String key) throws Exception {
        return http.perform(post("/api/device/" + deviceId + "/deactivate").contentType("application/json")
                .content("{\"expectedVersion\":" + expectedVersion + "}")
                .header("Idempotency-Key", key).principal(authentication(USER_ID)));
    }

    private ResultActions deactivateAfterTask(long expectedVersion, String key) throws Exception {
        return http.perform(post("/api/device/" + DEVICE_ID + "/deactivate-after-task").contentType("application/json")
                .content("{\"expectedVersion\":" + expectedVersion + "}")
                .header("Idempotency-Key", key).principal(authentication(USER_ID)));
    }

    private UsernamePasswordAuthenticationToken authentication(long userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        return authentication;
    }

    private void seedUser(long userId, String suffix) {
        jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox,created_at,is_deleted)"
                        + " VALUES(?, '86',?,'127.0.0.1','test',?,?,'ACTIVE',0,?,0)",
                userId, "device-event-" + suffix, "device-event-" + suffix, "DEVICE" + suffix,
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private void seedInactiveDevice() {
        seedDevice("DEACTIVATED", 0L);
    }

    private void seedActiveDeviceWithTasks() {
        seedDevice("ACTIVE", 7L);
        seedRuntime("ONLINE", "TASK-CLAIMED");
        seedActiveTask("TASK-CLAIMED", DEVICE_ID, "PRODUCTION", "CLAIMED");
    }

    private void seedDevice(String status, long version) {
        jdbc.update("INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,ownership_status,source_environment,run_id,"
                        + "status,pending_deactivate,row_version,is_deleted) VALUES(?,?, 'DEV-EVENT-930031','Device event test',"
                        + "'DEVICE','OWNED','PRODUCTION','',?,?,?,0)",
                DEVICE_ID, USER_ID, status, 0, version);
    }

    private void seedOtherProductionDevice() {
        jdbc.update("INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,ownership_status,source_environment,run_id,"
                        + "status,pending_deactivate,row_version,is_deleted) VALUES(?,?, 'DEV-EVENT-930032','Other device event test',"
                        + "'DEVICE','OWNED','PRODUCTION','', 'ACTIVE',0,0,0)",
                OTHER_DEVICE_ID, USER_ID);
    }

    private void seedRuntime(String onlineStatus, String taskNo) {
        jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,online_status,paused_reason,active_task_no,heartbeat_at,is_deleted)"
                        + " VALUES(?,?,NULL,?,NOW(),0)", DEVICE_ID, onlineStatus, taskNo);
    }

    private void seedActiveTask(String taskNo, long deviceId, String environment, String status) {
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,reward_usdt,source_environment,client_name,status,"
                        + "proof_consumed_at,created_at,updated_at,is_deleted) VALUES(?,?,?,'DEVICE_TEST',17.5,?,?,?,NULL,NOW(),NOW(),0)",
                taskNo, USER_ID, deviceId, environment, "device-event-test", status);
    }

    private void seedCompletedHistoricalTaskWithReceipt(String taskNo) {
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,reward_usdt,source_environment,client_name,status,"
                        + "proof_consumed_at,completed_at,created_at,updated_at,is_deleted) VALUES(?,?,?,'DEVICE_TEST',17.5,'PRODUCTION',"
                        + "'device-event-test','COMPLETED',NULL,NOW(),NOW(),NOW(),0)", taskNo, USER_ID, DEVICE_ID);
        jdbc.update("INSERT INTO nx_compute_receipt(user_id,user_device_id,task_no,receipt_no,task_type,client_name,reward_usdt,reward_nex,"
                        + "earning_status,source_environment,proof_hash,completed_at,is_deleted) VALUES(?,?,?, 'RECEIPT-930031',"
                        + "'DEVICE_TEST','device-event-test',17.5,0,'POSTED','PRODUCTION','proof',NOW(),0)", USER_ID, DEVICE_ID, taskNo);
    }

    private void assertDevice(String status, long version, int pending) {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=?", String.class, DEVICE_ID))
                .isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT row_version FROM nx_user_device WHERE id=?", Long.class, DEVICE_ID))
                .isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT pending_deactivate FROM nx_user_device WHERE id=?", Integer.class, DEVICE_ID))
                .isEqualTo(pending);
    }

    private void assertRuntime(String status, String taskNo) {
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime WHERE user_device_id=?", String.class,
                DEVICE_ID)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime WHERE user_device_id=?", String.class,
                DEVICE_ID)).isEqualTo(taskNo);
    }

    private void assertTask(String taskNo, String status, Boolean proofConsumed) {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?", String.class, taskNo))
                .isEqualTo(status);
        if (proofConsumed != null) {
            assertThat(jdbc.queryForObject("SELECT proof_consumed_at FROM nx_compute_task WHERE task_no=?",
                    LocalDateTime.class, taskNo) != null).isEqualTo(proofConsumed);
        }
    }

    private void assertOutbox(String eventName, int total, String previousStatus, String status, long version) throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isEqualTo(total);
        String payload = jdbc.queryForObject("SELECT payload FROM nx_event_outbox WHERE event_name=? ORDER BY id DESC LIMIT 1",
                String.class, eventName);
        JsonNode envelope = json.readTree(payload);
        assertThat(envelope.path("event_name").asText()).isEqualTo(eventName);
        assertThat(envelope.path("schema_revision").asInt()).isEqualTo(315);
        assertThat(envelope.path("user_id").asLong()).isEqualTo(USER_ID);
        assertThat(envelope.path("device_id").asLong()).isEqualTo(DEVICE_ID);
        assertThat(envelope.path("instance_no").asText()).isEqualTo("DEV-EVENT-930031");
        assertThat(envelope.path("previous_status").asText()).isEqualTo(previousStatus);
        assertThat(envelope.path("status").asText()).isEqualTo(status);
        assertThat(envelope.path("row_version").asLong()).isEqualTo(version);
    }

    private void assertOwnedFixtureDatabase() {
        if (fixtureDatabase == null || !OWNED_DATABASE.matcher(fixtureDatabase).matches()) {
            throw new IllegalStateException("Refusing to operate on a non-fixture database");
        }
    }

    private void dropOwnedFixtureDatabase() {
        if (!fixtureDatabaseCreated) return;
        assertOwnedFixtureDatabase();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureDatabase);
        jdbc.execute("USE information_schema");
        jdbc.execute("DROP DATABASE `" + fixtureDatabase + "`");
        fixtureDatabaseCreated = false;
    }
}
