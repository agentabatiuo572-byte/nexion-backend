package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.device.application.TestComputeWorkerService.*;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Local contract tests; production SQL/transaction integration remains a separate required gate. */
class TestComputeWorkerContinuousTest {
    private static final String TASK = "CTA-EXISTING";
    private static final String KEY = "TEST355-NEXT-" + "a".repeat(32);
    private final Clock clock = Clock.fixed(TestComputeWorkerServiceTest.INSTANT, ZoneOffset.UTC);
    private final LocalDateTime now = TestComputeWorkerServiceTest.NOW;
    private final AppTaskAssignmentMapper mapper = mock(AppTaskAssignmentMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final ComputeTaskProofVerifier proof = mock(ComputeTaskProofVerifier.class);
    private final org.springframework.mock.env.MockEnvironment environment = TestComputeWorkerServiceTest.continuousEnvironment();
    private final TestComputeWorkerService worker = new TestComputeWorkerService(mapper, audit, clock, environment);
    private final AppTaskAssignmentService app = new AppTaskAssignmentService(mapper, idempotency,
            mock(EventOutboxService.class), audit, proof, environment, clock, worker);
    private final ContinuousIdentity identity = new ContinuousIdentity("test355-continuous");
    private final AtomicReference<AssignmentRow> task = new AtomicReference<>();
    private final AtomicReference<TestWorkerRuntimeRow> runtime = new AtomicReference<>();

    @BeforeEach @SuppressWarnings({"rawtypes", "unchecked"}) void setup() {
        task.set(row("commercial-model", "UVEL App"));
        runtime.set(new TestWorkerRuntimeRow("ONLINE", TASK, "UVEL App", "", now.minusDays(1), null));
        when(mapper.userScope(CONTINUOUS_OWNER)).thenReturn(new UserScope(0));
        when(mapper.lockProductionUser(CONTINUOUS_OWNER)).thenReturn(CONTINUOUS_OWNER);
        when(mapper.lockOwnedDevice(CONTINUOUS_OWNER, CONTINUOUS_DEVICE)).thenReturn(new DeviceRow(CONTINUOUS_DEVICE,
                CONTINUOUS_INSTANCE, "SHARE", "SHARE", "Cloud Share", "ACTIVE", "cloud-share", now.minusDays(1), now.minusDays(1),
                0, "TEST", "ONLINE", null, false));
        when(mapper.lockPaidCloudShareDailyNex(CONTINUOUS_OWNER, CONTINUOUS_DEVICE)).thenReturn(new BigDecimal("3"));
        when(proof.sourceEnvironment()).thenReturn("PRODUCTION");
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(mapper.lockActiveAssignment(CONTINUOUS_OWNER, CONTINUOUS_DEVICE, "PRODUCTION")).thenAnswer(i -> task.get());
        when(mapper.lockAssignment(CONTINUOUS_OWNER, TASK, "PRODUCTION")).thenAnswer(i -> task.get());
        when(mapper.lockTestWorkerRuntime(CONTINUOUS_OWNER, CONTINUOUS_DEVICE, CONTINUOUS_INSTANCE)).thenAnswer(i -> runtime.get());
        when(mapper.markTestWorkerOnline(eq(CONTINUOUS_OWNER), eq(CONTINUOUS_DEVICE), eq(CONTINUOUS_INSTANCE), eq(TASK), anyString(), any()))
                .thenAnswer(i -> { runtime.set(new TestWorkerRuntimeRow("ONLINE", TASK, CLIENT, i.getArgument(4), i.getArgument(5), null)); return 1; });
        when(mapper.markTestWorkerTask(eq(CONTINUOUS_OWNER), eq(CONTINUOUS_DEVICE), eq(TASK), eq(CONTINUOUS_CONFIG), any()))
                .thenAnswer(i -> { task.set(row(KIND, CLIENT)); return 1; });
    }

    private AssignmentRow row(String model, String client) {
        return new AssignmentRow(TASK, CONTINUOUS_DEVICE, CONTINUOUS_CONFIG, "original name", "EM", model, client,
                "RUNNING", new BigDecimal("0.045005"), 5, 0, now.minusHours(1), now.plusHours(23), null, null,
                TestComputeWorkerServiceTest.NONCE, now.plusHours(23));
    }

    @Test void currentPaidShareWithPhysicalZeroVramUsesTheSameClaimAndReturnsItsActualBusinessProof() {
        var data = app.continuousWorkerNext(identity, KEY).getData();
        assertThat(data).containsEntry("ownerId", CONTINUOUS_OWNER).containsEntry("deviceId", CONTINUOUS_DEVICE)
                .containsEntry("taskNo", TASK).containsEntry("proofNonce", TestComputeWorkerServiceTest.NONCE)
                .containsEntry("deploymentScope", "TEST").containsEntry("serverCanonical", true);
        assertThat((long) data.get("jobExpiresAt") - (long) data.get("jobIssuedAt")).isEqualTo(Duration.ofHours(24).toMillis());
        verify(mapper, never()).insertAssignment(anyString(), any(), any(), any(), any(), any(), any(), anyString(), any(), anyString(), any(), any());
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test void secondIndependentClaimCannotTakeOverTheAlreadyMarkedTaskOrRemintItsProof() {
        task.set(row(KIND, CLIENT));
        assertThatThrownBy(() -> app.continuousWorkerNext(identity, KEY)).hasMessage("TEST_COMPUTE_WORKER_ALREADY_CLAIMED");
        verify(mapper, never()).markTestWorkerTask(any(), any(), any(), any(), any());
        verify(mapper, never()).markTestWorkerOnline(any(), any(), any(), any(), any(), any());
    }

    @Test void normalTaskLockReturnsIdleWithoutRuntimeWrites() {
        when(mapper.lockDeviceTaskLock(CONTINUOUS_OWNER, CONTINUOUS_DEVICE, "PRODUCTION"))
                .thenReturn(new DeviceLockRow(now.plusMinutes(1), "CTA-PREVIOUS"));
        assertThat(app.continuousWorkerNext(identity, KEY).getData()).containsEntry("idle", true);
        verify(mapper, never()).markTestWorkerOnline(any(), any(), any(), any(), any(), any());
    }

    @Test void absentTaskUsesTheOriginalRoutingCapacityNonceAndInsertRatherThanInventingAReceipt() {
        task.set(null);
        runtime.set(new TestWorkerRuntimeRow("OFFLINE", null, CLIENT, "", now.minusDays(1), null));
        when(mapper.lockAssignment(eq(CONTINUOUS_OWNER), anyString(), eq("PRODUCTION"))).thenAnswer(i -> task.get());
        when(mapper.eligibleTasks(8)).thenReturn(List.of(new TaskConfigRow(CONTINUOUS_CONFIG, "EM", "EM", "normal-model",
                new BigDecimal("0.045"), new BigDecimal("0.04501"), 8, "ACTIVE", "pending")));
        Map<String, String> capacity = Map.ofEntries(Map.entry("capacityBand1DeltaPct", "-3"), Map.entry("capacityBand2DeltaPct", "-6"),
                Map.entry("capacityBand3DeltaPct", "-23.7"), Map.entry("stageEarlyEnd", "3"), Map.entry("stageMidEnd", "8"),
                Map.entry("cycleMonths", "13"), Map.entry("capacityFloorPct", "22"), Map.entry("capacitySubsidyDays", "30"),
                Map.entry("taskLockS1", "30"), Map.entry("taskLockPro", "150"), Map.entry("taskLockRack", "480"),
                Map.entry("capacityApplyToPhone", "false"), Map.entry("capacityApplyToCloudShare", "false"),
                Map.entry("capacityApplyToPcGpu", "false"), Map.entry("capacityApplyToS1", "true"), Map.entry("capacityApplyToPro", "true"),
                Map.entry("capacityApplyToProV2", "true"), Map.entry("capacityApplyToRackP1", "true"), Map.entry("capacityApplyToRackP2", "true"));
        when(mapper.e3CapacityConfig()).thenReturn(capacity.entrySet().stream().map(e -> new ConfigRow(e.getKey(), e.getValue())).toList());
        when(mapper.markTestWorkerOnline(eq(CONTINUOUS_OWNER), eq(CONTINUOUS_DEVICE), eq(CONTINUOUS_INSTANCE), anyString(), anyString(), any()))
                .thenAnswer(i -> { runtime.set(new TestWorkerRuntimeRow("ONLINE", i.getArgument(3), CLIENT, i.getArgument(4), i.getArgument(5), null)); return 1; });
        when(mapper.insertAssignment(anyString(), eq(CONTINUOUS_OWNER), eq(CONTINUOUS_DEVICE), any(), any(), any(), any(), anyString(), any(), eq("PRODUCTION"), any(), any()))
                .thenAnswer(i -> {
                    task.set(new AssignmentRow(i.getArgument(0), CONTINUOUS_DEVICE, CONTINUOUS_CONFIG, "EM", "EM", "normal-model", "UVEL App",
                            "RUNNING", i.getArgument(4), i.getArgument(5), i.getArgument(6), i.getArgument(10), i.getArgument(11), null, null,
                            i.getArgument(7), i.getArgument(8))); return 1;
                });
        when(mapper.markTestWorkerTask(eq(CONTINUOUS_OWNER), eq(CONTINUOUS_DEVICE), anyString(), eq(CONTINUOUS_CONFIG), any()))
                .thenAnswer(i -> { AssignmentRow t = task.get(); task.set(new AssignmentRow(t.taskNo(), t.deviceId(), t.taskId(), TASK_NAME, t.taskClass(),
                        KIND, CLIENT, t.status(), t.rewardUsdt(), t.requiredSeconds(), t.taskLockMinutes(), t.startedAt(), t.leaseExpiresAt(), null, null,
                        t.completionNonce(), t.proofExpiresAt())); return 1; });
        var data = app.continuousWorkerNext(identity, KEY).getData();
        assertThat((String) data.get("taskNo")).matches("CTA-TEST-[A-F0-9]{32}");
        assertThat((String) data.get("proofNonce")).matches("[a-f0-9]{64}");
        assertThat(task.get().rewardUsdt()).isEqualByComparingTo("0.045005");
        verify(mapper).eligibleTasks(8);
        verify(mapper, never()).insertReceipt(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void receiptReadbackIsRestrictedToTheActualTaskDeviceAndDeterministicKind() {
        var foreign = new ReceiptRow("CTR-" + TASK, TASK, 1153L, CONTINUOUS_INSTANCE, "Share", "SHARE", "", 0,
                CONTINUOUS_CONFIG, TASK_NAME, "EM", KIND, CLIENT, new BigDecimal("0.045005"), BigDecimal.ZERO,
                "CREDITED", "f".repeat(64), now.minusMinutes(1), now, 5);
        when(mapper.receipt(CONTINUOUS_OWNER, "CTR-" + TASK)).thenReturn(foreign);
        assertThat(app.continuousWorkerReceipt(identity, TASK).getData()).containsEntry("receiptFound", false)
                .doesNotContainKeys("proofNonce", "proofHash", "receiptNo");
        verifyNoInteractions(audit);
    }

    @Test void actualBoundReceiptReadbackReturnsOnlySafeConfirmationFields() {
        var receipt = new ReceiptRow("CTR-" + TASK, TASK, CONTINUOUS_DEVICE, CONTINUOUS_INSTANCE, "Share", "SHARE", "", 0,
                CONTINUOUS_CONFIG, TASK_NAME, "EM", KIND, CLIENT, new BigDecimal("0.045005"), new BigDecimal("3"),
                "CREDITED", "f".repeat(64), now.minusMinutes(1), now, 5);
        when(mapper.receipt(CONTINUOUS_OWNER, "CTR-" + TASK)).thenReturn(receipt);
        assertThat(app.continuousWorkerReceipt(identity, TASK).getData()).containsEntry("receiptFound", true)
                .containsEntry("earningStatus", "CREDITED").containsEntry("proofHash", "f".repeat(64))
                .doesNotContainKeys("proofNonce", "inputBytesBase64", "resultBytesBase64");
        verifyNoInteractions(audit);
    }
}
