package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.dto.AppCapacityReplaceSubmitRequest;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.CanonicalEventSchemaMySqlFixture;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.Environment;

/**
 * A4 schema regressions for actual settlement writers. The fixture copies DDL only into an owned
 * random database, so these tests never read or mutate a business row in the source database.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class DeviceSettlementEventSchemaMySqlIntegrationTest {
    private static final long USER_ID = 981_031L;
    private static final long DEVICE_ID = 982_031L;
    private static final long TARGET_PRODUCT_ID = 983_031L;
    private static final String TASK_NO = "CTA-A4-SETTLEMENT-981031";
    private static final String COMPLETION_NONCE = "completion-nonce-a4-settlement";
    private static final String TASK_COMPLETED = "task.completed";
    private static final String EARNINGS_CREDITED = "earnings.credited";
    private static final String CAPACITY_REPLACED = "capacity_replacement.completed";
    private static final BigDecimal REWARD = new BigDecimal("2.500000");
    private static final BigDecimal INITIAL_WALLET = new BigDecimal("10.000000");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static final String[] TASK_TABLES = {
            "nx_user", "nx_user_device", "nx_user_device_runtime", "nx_compute_task",
            "nx_compute_receipt", "nx_user_wallet", "nx_wallet_ledger", "nx_earning_event",
            "nx_compute_device_task_lock", "nx_onboarding_calibration"
    };
    private static final String[] CAPACITY_TABLES = {
            "nx_user", "nx_user_device", "nx_compute_task", "nx_user_wallet", "nx_wallet_ledger",
            "nx_product", "nx_order", "nx_order_item", "nx_tradein_application", "nx_trade_in_order"
    };

    @Test
    void missingTaskCompletedSchemaRollsBackRealSettlementWritesAndOutbox() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = new CanonicalEventSchemaMySqlFixture(TASK_TABLES)) {
            seedTaskSettlement(fixture);

            assertThatThrownBy(() -> taskService(fixture).complete(USER_ID, TASK_NO, "missing-task-completed",
                    completionRequest()))
                    .hasMessageContaining("A4_SCHEMA_NOT_REGISTERED");

            assertTaskSettlementRolledBack(fixture);
        }
    }

    @Test
    void missingSecondEarningsEventRollsBackTheAlreadyPersistedTaskCompletedOutboxRow() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = new CanonicalEventSchemaMySqlFixture(TASK_TABLES)) {
            seedTaskSettlement(fixture);
            fixture.migrate();
            fixture.jdbc().update("UPDATE nx_event_schema_registry SET is_deleted=1 WHERE event_name=?", EARNINGS_CREDITED);

            assertThatThrownBy(() -> taskService(fixture).complete(USER_ID, TASK_NO, "missing-earnings-credited",
                    completionRequest()))
                    .hasMessageContaining("A4_SCHEMA_NOT_REGISTERED");

            // task.completed is inserted before earnings.credited; a zero outbox count proves it joined
            // the same Spring transaction as the receipt, wallet, ledger and earning rows.
            assertTaskSettlementRolledBack(fixture);
        }
    }

    @Test
    void migrationRegistersBothActualSettlementPublishersWithSixDecimalAmounts() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = new CanonicalEventSchemaMySqlFixture(TASK_TABLES)) {
            seedTaskSettlement(fixture);
            fixture.migrate();

            taskService(fixture).complete(USER_ID, TASK_NO, "settlement-after-migration", completionRequest());

            assertThat(fixture.jdbc().queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",
                    String.class, TASK_NO)).isEqualTo("COMPLETED");
            assertThat(fixture.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",
                    BigDecimal.class, USER_ID)).isEqualByComparingTo("12.500000");
            assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_compute_receipt", Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc().queryForObject("SELECT reward_usdt FROM nx_compute_receipt WHERE task_no=?",
                    BigDecimal.class, TASK_NO)).isEqualByComparingTo(REWARD);
            assertThat(fixture.jdbc().queryForObject("SELECT earning_status FROM nx_compute_receipt WHERE task_no=?",
                    String.class, TASK_NO)).isEqualTo("CREDITED");
            assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger", Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc().queryForObject("SELECT amount FROM nx_wallet_ledger WHERE biz_no=?",
                    BigDecimal.class, TASK_NO)).isEqualByComparingTo(REWARD);
            assertThat(fixture.jdbc().queryForObject("SELECT balance_after FROM nx_wallet_ledger WHERE biz_no=?",
                    BigDecimal.class, TASK_NO)).isEqualByComparingTo("12.500000");
            assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_earning_event", Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc().queryForObject("SELECT amount FROM nx_earning_event WHERE receipt_no=("
                    + "SELECT receipt_no FROM nx_compute_receipt WHERE task_no=?)", BigDecimal.class, TASK_NO))
                    .isEqualByComparingTo(REWARD);
            assertThat(fixture.jdbc().query("SELECT event_name,payload FROM nx_event_outbox ORDER BY id",
                    (rs, row) -> Map.entry(rs.getString("event_name"), rs.getString("payload"))))
                    .extracting(Map.Entry::getKey).containsExactly(TASK_COMPLETED, EARNINGS_CREDITED);
            for (String payload : fixture.jdbc().queryForList("SELECT payload FROM nx_event_outbox ORDER BY id", String.class)) {
                JsonNode event = JSON.readTree(payload);
                assertThat(event.path("amount_usdt").decimalValue()).isEqualByComparingTo(REWARD);
                assertThat(event.path("task_no").asText()).isEqualTo(TASK_NO);
                assertThat(event.path("receipt_no").asText()).isNotBlank();
            }
        }
    }

    @Test
    void capacityReplacementTargetPublisherFailsBeforeMigrationAndSucceedsAfterMigration() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = new CanonicalEventSchemaMySqlFixture(CAPACITY_TABLES)) {
            seedCapacityReplacement(fixture);

            assertThatThrownBy(() -> capacityService(fixture).capacityReplace(USER_ID, "capacity-schema-missing",
                    new AppCapacityReplaceSubmitRequest(DEVICE_ID, "sku-a4-capacity", new BigDecimal("80.000000"))))
                    .hasMessageContaining("A4_SCHEMA_NOT_REGISTERED");
            assertCapacityReplacementRolledBack(fixture);

            fixture.migrate();
            capacityService(fixture).capacityReplace(USER_ID, "capacity-schema-present",
                    new AppCapacityReplaceSubmitRequest(DEVICE_ID, "sku-a4-capacity", new BigDecimal("80.000000")));

            assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name=?",
                    Integer.class, CAPACITY_REPLACED)).isEqualTo(1);
            String payload = fixture.jdbc().queryForObject("SELECT payload FROM nx_event_outbox WHERE event_name=?",
                    String.class, CAPACITY_REPLACED);
            JsonNode event = JSON.readTree(payload);
            assertThat(event.path("operation").asText()).isEqualTo("CAPACITY_REPLACE");
            assertThat(event.path("wallet_debit_usdt").decimalValue())
                    .isEqualByComparingTo(new BigDecimal("80.000000"));
            assertThat(fixture.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",
                    BigDecimal.class, USER_ID)).isEqualByComparingTo("120.000000");
            assertThat(fixture.jdbc().queryForObject("SELECT amount FROM nx_wallet_ledger", BigDecimal.class))
                    .isEqualByComparingTo("80.000000");
            assertThat(fixture.jdbc().queryForObject("SELECT balance_after FROM nx_wallet_ledger", BigDecimal.class))
                    .isEqualByComparingTo("120.000000");
            assertThat(fixture.jdbc().queryForObject("SELECT status FROM nx_user_device WHERE id=?", String.class, DEVICE_ID))
                    .isEqualTo("INVENTORY");

            // checkout.completed and device.purchase_completed are deliberately suppressed only here. This
            // isolates the new target contract while still exercising its real publisher and all SQL writes.
            assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isEqualTo(1);
        }
    }

    private AppTaskAssignmentService taskService(CanonicalEventSchemaMySqlFixture fixture) {
        AppTaskAssignmentMapper mapper = mock(AppTaskAssignmentMapper.class, delegatesTo(
                fixture.mapper(AppTaskAssignmentMapper.class)));
        doReturn(new AppTaskAssignmentMapper.UserScope(0)).when(mapper).userScope(USER_ID);
        doReturn(USER_ID).when(mapper).lockProductionUser(USER_ID);
        doReturn(DEVICE_ID).when(mapper).assignmentDeviceId(USER_ID, TASK_NO, "PRODUCTION");
        doReturn(taskDevice()).when(mapper).lockOwnedDevice(USER_ID, DEVICE_ID);
        doReturn(taskAssignment()).when(mapper).lockAssignment(USER_ID, TASK_NO, "PRODUCTION");
        doReturn(new AppTaskAssignmentMapper.TaskRuntimeGateRow("ACTIVE", "pending", 8, 64))
                .when(mapper).taskRuntimeGate(USER_ID, DEVICE_ID, "task-a4-settlement");
        doReturn("DEV-A4-SETTLEMENT-982031").when(mapper).deviceInstanceNo(USER_ID, DEVICE_ID);
        doReturn(new AppTaskAssignmentMapper.UserEventAttribution("P3", 7, "2026-W35"))
                .when(mapper).userEventAttribution(USER_ID);
        doReturn(0).when(mapper).deactivatePendingDevice(USER_ID, DEVICE_ID, NOW);

        ComputeTaskProofVerifier proof = mock(ComputeTaskProofVerifier.class);
        when(proof.sourceEnvironment()).thenReturn("PRODUCTION");
        when(proof.verify(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new ComputeTaskProofVerifier.Verification(false, "a".repeat(64)));
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        return fixture.transactional(new AppTaskAssignmentService(mapper, directIdempotency(), fixture.outbox(),
                mock(AuditLogService.class), proof, environment, Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"),
                        ZoneOffset.UTC)));
    }

    private AppTradeinService capacityService(CanonicalEventSchemaMySqlFixture fixture) {
        AppTradeinMapper mapper = mock(AppTradeinMapper.class, delegatesTo(fixture.mapper(AppTradeinMapper.class)));
        doReturn(0).when(mapper).activeUserEnvironment(USER_ID);
        doReturn(USER_ID).when(mapper).lockActiveUser(USER_ID);
        doReturn(6).when(mapper).countActiveDevices(USER_ID);
        doReturn(new BigDecimal("200.000000")).when(mapper).lockWalletBalanceUsdt(USER_ID);
        doReturn(capacitySource()).when(mapper).lockCapacityReplacementSource(USER_ID);
        doReturn(capacityTarget()).when(mapper).lockTargetProduct(null, "sku-a4-capacity");
        doReturn(new AppTradeinMapper.UserEventAttribution("P3", 7, "2026-W35"))
                .when(mapper).userEventAttribution(USER_ID);
        doReturn(null).when(mapper).purchaseGateJson("sku-a4-capacity");

        EventOutboxService outbox = spy(fixture.outbox());
        // Keep capacity_replacement.completed completely unstubbed: this spy delegates that invocation to
        // fixture.outbox(), whose mapper/ObjectMapper/A4 dependencies are real. The two established companion
        // contracts are intentionally outside this focused migration test.
        doAnswer(invocation -> "suppressed-non-target-event")
                .when(outbox).publishUserEvent(anyString(), anyString(), eq("checkout.completed"), anyLong(), anyString(),
                        anyInt(), anyString(), any());
        doAnswer(invocation -> "suppressed-non-target-event")
                .when(outbox).publishUserEvent(anyString(), anyString(), eq("device.purchase_completed"), anyLong(),
                        anyString(), anyInt(), anyString(), any());
        FundsSandboxProfileGuard sandbox = mock(FundsSandboxProfileGuard.class);
        when(sandbox.isLocalSandboxEnabled()).thenReturn(false);
        when(sandbox.isStrictProductionRuntime()).thenReturn(true);
        StorefrontProductReleasePolicy release = mock(StorefrontProductReleasePolicy.class);
        when(release.evaluate(anyString(), any())).thenReturn(StorefrontProductReleasePolicy.Decision.open(null));
        return fixture.transactional(new AppTradeinService(mapper, directIdempotency(), outbox, mock(AuditLogService.class),
                release, sandbox));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AdminIdempotencyService directIdempotency() {
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> ((Supplier) call.getArgument(4)).get());
        return idempotency;
    }

    private void seedTaskSettlement(CanonicalEventSchemaMySqlFixture fixture) {
        fixture.jdbc().update("""
                INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox,
                    created_at,updated_at,is_deleted)
                VALUES (?, '86', 'a4-task-user', '127.0.0.1', 'test', 'A4 Task User', 'A4TASK', 'ACTIVE', 0,
                    ?, ?, 0)
                """, USER_ID, NOW.minusDays(300), NOW.minusDays(300));
        fixture.jdbc().update("""
                INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,ownership_status,source_environment,run_id,
                    status,activated_at,purchased_at,vram_total_gb,pending_deactivate,created_at,updated_at,is_deleted)
                VALUES (?, ?, 'DEV-A4-SETTLEMENT-982031', 'A4 settlement device', 'DEVICE', 'OWNED', 'PRODUCTION', '',
                    'ACTIVE', ?, ?, 64, 0, ?, ?, 0)
                """, DEVICE_ID, USER_ID, NOW.minusDays(30), NOW.minusDays(31), NOW.minusDays(31), NOW.minusDays(31));
        fixture.jdbc().update("""
                INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available,pending_withdraw,lifetime_earned,version,
                    created_at,updated_at,is_deleted)
                VALUES (?, ?, 0, 0, 0, 0, ?, ?, 0)
                """, USER_ID, INITIAL_WALLET, NOW.minusDays(1), NOW.minusDays(1));
        // client_observed_at is virtual in the live table. Seed its source timestamps explicitly and never insert it.
        fixture.jdbc().update("""
                INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,task_config_id,task_name,model_name,
                    reward_usdt,required_seconds,task_lock_minutes,completion_nonce,proof_expires_at,source_environment,
                    client_name,status,started_at,worker_ack_at,lease_expires_at,attempt_count,max_attempts,
                    created_at,updated_at,is_deleted)
                VALUES (?, ?, ?, 'IG', 'task-a4-settlement', 'A4 settlement task', 'model-a4',
                    ?, 18, 5, ?, ?, 'PRODUCTION', 'Nexion App', 'RUNNING', ?, ?, ?, 1, 3, ?, ?, 0)
                """, TASK_NO, USER_ID, DEVICE_ID, REWARD, COMPLETION_NONCE, NOW.plusHours(2),
                NOW.minusMinutes(30), NOW.minusMinutes(30), NOW.plusHours(2), NOW.minusMinutes(30), NOW.minusMinutes(30));
    }

    private void seedCapacityReplacement(CanonicalEventSchemaMySqlFixture fixture) {
        fixture.jdbc().update("""
                INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox,user_level,
                    created_at,updated_at,is_deleted)
                VALUES (?, '86', 'a4-capacity-user', '127.0.0.1', 'test', 'A4 Capacity User', 'A4CAP', 'ACTIVE', 0, 'L4',
                    ?, ?, 0)
                """, USER_ID, NOW.minusDays(300), NOW.minusDays(300));
        fixture.jdbc().update("""
                INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,ownership_status,source_environment,run_id,
                    status,activated_at,purchased_at,price_usdt_snapshot,pending_deactivate,created_at,updated_at,is_deleted)
                VALUES (?, ?, 'DEV-A4-CAPACITY-982031', 'A4 capacity source', 'DEVICE', 'OWNED', 'PRODUCTION', '',
                    'ACTIVE', ?, ?, 100, 0, ?, ?, 0)
                """, DEVICE_ID, USER_ID, NOW.minusDays(30), NOW.minusDays(31), NOW.minusDays(31), NOW.minusDays(31));
        fixture.jdbc().update("""
                INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available,pending_withdraw,lifetime_earned,version,
                    created_at,updated_at,is_deleted)
                VALUES (?, 200, 0, 0, 0, 0, ?, ?, 0)
                """, USER_ID, NOW.minusDays(1), NOW.minusDays(1));
        fixture.jdbc().update("""
                INSERT INTO nx_product(id,product_no,name,product_type,tier,price_usdt,status,stock,store_visible,
                    inventory_mode,sold_count,created_at,updated_at,is_deleted)
                VALUES (?, 'sku-a4-capacity', 'A4 capacity target', 'DEVICE', 'PRO', 80, 'ACTIVE', 3, 1,
                    'FINITE', 0, ?, ?, 0)
                """, TARGET_PRODUCT_ID, NOW.minusDays(1), NOW.minusDays(1));
    }

    private void assertTaskSettlementRolledBack(CanonicalEventSchemaMySqlFixture fixture) {
        assertThat(fixture.jdbc().queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?", String.class, TASK_NO))
                .isEqualTo("RUNNING");
        assertThat(fixture.jdbc().queryForObject("SELECT completed_at FROM nx_compute_task WHERE task_no=?",
                LocalDateTime.class, TASK_NO)).isNull();
        assertThat(fixture.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",
                BigDecimal.class, USER_ID)).isEqualByComparingTo(INITIAL_WALLET);
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_compute_receipt", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_earning_event", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    private void assertCapacityReplacementRolledBack(CanonicalEventSchemaMySqlFixture fixture) {
        assertThat(fixture.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",
                BigDecimal.class, USER_ID)).isEqualByComparingTo("200.000000");
        assertThat(fixture.jdbc().queryForObject("SELECT stock FROM nx_product WHERE id=?", Integer.class, TARGET_PRODUCT_ID))
                .isEqualTo(3);
        assertThat(fixture.jdbc().queryForObject("SELECT status FROM nx_user_device WHERE id=?", String.class, DEVICE_ID))
                .isEqualTo("ACTIVE");
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_order", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_tradein_application", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    private AppTaskAssignmentMapper.DeviceRow taskDevice() {
        return new AppTaskAssignmentMapper.DeviceRow(DEVICE_ID, "DEV-A4-SETTLEMENT-982031", "DEVICE", "PRO",
                "A4 settlement device", "ACTIVE", "sku-a4-settlement", NOW.minusDays(31), NOW.minusDays(30), 64,
                null, "ONLINE", null, false);
    }

    private AppTaskAssignmentMapper.AssignmentRow taskAssignment() {
        return new AppTaskAssignmentMapper.AssignmentRow(TASK_NO, DEVICE_ID, "task-a4-settlement", "A4 settlement task",
                "IG", "model-a4", "Nexion App", "RUNNING", REWARD, 18, 5, NOW.minusMinutes(30), NOW.plusHours(2),
                null, null, COMPLETION_NONCE, NOW.plusHours(2));
    }

    private AppTaskCompleteRequest completionRequest() {
        return new AppTaskCompleteRequest("result-a4", "SERVER", "executor-a4", COMPLETION_NONCE,
                NOW.toInstant(ZoneOffset.UTC).toEpochMilli(), "signature-a4");
    }

    private AppTradeinMapper.SourceDevice capacitySource() {
        return new AppTradeinMapper.SourceDevice(DEVICE_ID, USER_ID, "DEV-A4-CAPACITY-982031", 12L,
                "sku-a4-source", "A4 capacity source", "S1", "ACTIVE", new BigDecimal("100.000000"));
    }

    private AppTradeinMapper.TargetProduct capacityTarget() {
        return new AppTradeinMapper.TargetProduct(TARGET_PRODUCT_ID, "sku-a4-capacity", "A4 capacity target", "PRO",
                "ACTIVE", new BigDecimal("80.000000"), 3, null, "DEVICE", 2, "GPU-A4", 64,
                BigDecimal.ONE, new BigDecimal("1.000000"), BigDecimal.ZERO, "FINITE");
    }
}
