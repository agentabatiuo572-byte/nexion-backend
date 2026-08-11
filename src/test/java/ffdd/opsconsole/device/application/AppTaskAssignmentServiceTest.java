package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.dto.AppTaskClaimRequest;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppTaskAssignmentServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 12, 0);
    private final AppTaskAssignmentMapper mapper = mock(AppTaskAssignmentMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ComputeTaskProofVerifier proofVerifier = mock(ComputeTaskProofVerifier.class);
    private final AppTaskAssignmentService service = new AppTaskAssignmentService(
            mapper, idempotency, outbox, audit, proofVerifier,
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(mapper.lockOwnedDevice(7L, 11L)).thenReturn(device("stellarbox-s1", "S1", "StellarBox S1", 96));
        when(mapper.eligibleTasks(96)).thenReturn(List.of(task("TASK-IG", "IG", 24, "pending")));
        when(mapper.taskLockConfig()).thenReturn(lockConfig());
        when(mapper.taskRuntimeGate(7L, 11L, "TASK-IG"))
                .thenReturn(new AppTaskAssignmentMapper.TaskRuntimeGateRow("active", "pending", 24, 96));
        when(proofVerifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.insertAssignment(anyString(), any(), any(), any(), any(), any(), any(),
                anyString(), any(), anyString(), any(), any())).thenReturn(1);
    }

    @Test
    void claimPersistsCanonicalConfigSnapshotAndBindsRuntimeConsumer() {
        var result = service.claim(7L, "claim-11", new AppTaskClaimRequest(11L));

        assertThat(result.getData().taskId()).isEqualTo("TASK-IG");
        assertThat(result.getData().requiredSeconds()).isEqualTo(18);
        assertThat(result.getData().rewardUsdt()).isEqualByComparingTo("0.300000");
        verify(mapper).insertAssignment(anyString(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(30), anyString(), any(), anyString(), any(), any());
        verify(mapper).bindRuntimeTask(any(), anyString(), any());
        verify(audit).recordRequired(any());
    }

    @Test
    void killedTaskIsBlockedEvenIfAStaleMapperReturnsIt() {
        when(mapper.eligibleTasks(96)).thenReturn(List.of(task("TASK-KILLED", "IG", 24, "已 kill")));

        assertThatThrownBy(() -> service.claim(7L, "claim-killed", new AppTaskClaimRequest(11L)))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_NO_ELIGIBLE_TASK");
        verify(mapper, never()).insertAssignment(anyString(), any(), any(), any(), any(), any(), any(),
                anyString(), any(), anyString(), any(), any());
    }

    @Test
    void insufficientVramIsBlockedEvenIfAStaleMapperReturnsIt() {
        when(mapper.eligibleTasks(96)).thenReturn(List.of(task("TASK-VRAM", "VG", 192, "pending")));

        assertThatThrownBy(() -> service.claim(7L, "claim-vram", new AppTaskClaimRequest(11L)))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_NO_ELIGIBLE_TASK");
    }

    @Test
    void activeServerLockRejectsClaimBeforeReadingTaskConfig() {
        when(mapper.lockDeviceTaskLock(7L, 11L, "PRODUCTION"))
                .thenReturn(new AppTaskAssignmentMapper.DeviceLockRow(NOW.plusMinutes(1), "OLD"));

        assertThatThrownBy(() -> service.claim(7L, "claim-locked", new AppTaskClaimRequest(11L)))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_DEVICE_LOCKED_UNTIL");
        verify(mapper, never()).eligibleTasks(any());
    }

    @Test
    void completionCreditsReceiptLedgerAndLockExactlyOnceAcrossDifferentKeys() {
        var running = assignment("RUNNING", null, null);
        var completed = assignment("COMPLETED", NOW, "CTR-1");
        when(mapper.lockAssignment(7L, "CTA-1", "PRODUCTION")).thenReturn(running, completed);
        when(mapper.deviceInstanceNo(7L, 11L)).thenReturn("DEV-11");
        when(proofVerifier.verify(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new ComputeTaskProofVerifier.Verification(false, "b".repeat(64)));
        when(mapper.insertReceipt(any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.creditWallet(any(), any(), any())).thenReturn(1);
        when(mapper.walletUsdt(7L)).thenReturn(new BigDecimal("10.300000"));
        when(mapper.insertWalletLedger(any(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.insertEarningEvent(anyString(), any(), any(), anyString(), any(), any())).thenReturn(1);
        when(mapper.completeAssignment(any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.userEventAttribution(7L)).thenReturn(
                new AppTaskAssignmentMapper.UserEventAttribution("P3", 8, "2026-W30"));

        var proof = validProof();
        service.complete(7L, "CTA-1", "complete-a", proof);
        assertThatThrownBy(() -> service.complete(7L, "CTA-1", "complete-b", proof))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_PROOF_REPLAYED");

        verify(mapper, times(1)).insertReceipt(any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        verify(mapper, times(1)).creditWallet(any(), any(), any());
        verify(mapper, times(1)).insertWalletLedger(any(), anyString(), any(), any(), any());
        verify(mapper, times(1)).upsertDeviceTaskLock(any(), any(), anyString(), any(), anyString(), any());
        verify(outbox, times(2)).publishUserEvent(anyString(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), any());
    }

    @Test
    void sandboxCompletionWritesOnlyTheIsolatedLedgerAndNeverCreditsWalletOrProductionEvents() {
        when(proofVerifier.sourceEnvironment()).thenReturn("SANDBOX");
        when(mapper.lockAssignment(7L, "CTA-1", "SANDBOX")).thenReturn(assignment("RUNNING", null, null));
        when(mapper.deviceInstanceNo(7L, 11L)).thenReturn("DEV-11");
        when(proofVerifier.verify(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new ComputeTaskProofVerifier.Verification(true, "b".repeat(64)));
        when(mapper.insertReceipt(any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertSandboxReward(anyString(), any(), any(), anyString(), any(), anyString(), any())).thenReturn(1);
        when(mapper.completeAssignment(any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.userEventAttribution(7L)).thenReturn(
                new AppTaskAssignmentMapper.UserEventAttribution("P3", 8, "2026-W30"));

        service.complete(7L, "CTA-1", "sandbox-complete", validProof());

        verify(mapper).insertSandboxReward(anyString(), any(), any(), anyString(), any(), anyString(), any());
        verify(mapper, never()).creditWallet(any(), any(), any());
        verify(mapper, never()).insertWalletLedger(any(), anyString(), any(), any(), any());
        verify(outbox, never()).publishUserEvent(anyString(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), any());
    }

    @Test
    void completionRechecksLiveKillAndVramBeforeProofOrWalletMutation() {
        when(mapper.lockAssignment(7L, "CTA-1", "PRODUCTION")).thenReturn(assignment("RUNNING", null, null));
        when(mapper.taskRuntimeGate(7L, 11L, "TASK-IG"))
                .thenReturn(new AppTaskAssignmentMapper.TaskRuntimeGateRow("active", "已 kill", 192, 96));

        assertThatThrownBy(() -> service.complete(7L, "CTA-1", "killed-complete", validProof()))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_KILLED");
        verify(proofVerifier, never()).verify(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any());
        verify(mapper, never()).creditWallet(any(), any(), any());
    }

    @Test
    void expiredRunningAssignmentIsCasExpiredThenAReplacementIsClaimed() {
        var expired = new AppTaskAssignmentMapper.AssignmentRow("CTA-OLD", 11L, "TASK-IG", "Old", "IG",
                "model", "Nexion App", "RUNNING", new BigDecimal("0.2"), 18, 30,
                NOW.minusDays(2), NOW.minusDays(1), null, null, "a".repeat(64), NOW.minusDays(1));
        when(mapper.lockActiveAssignment(7L, 11L, "PRODUCTION")).thenReturn(expired);
        when(mapper.expireAssignment(7L, "CTA-OLD", "PRODUCTION", NOW)).thenReturn(1);

        var result = service.claim(7L, "claim-after-expiry", new AppTaskClaimRequest(11L));

        assertThat(result.getData().taskNo()).isNotEqualTo("CTA-OLD");
        verify(mapper).expireAssignment(7L, "CTA-OLD", "PRODUCTION", NOW);
        verify(mapper).clearRuntimeTask(11L, "CTA-OLD", NOW);
        verify(mapper).insertAssignment(anyString(), any(), any(), any(), any(), any(), any(),
                anyString(), any(), anyString(), any(), any());
    }

    @Test
    void sandboxCannotSeeOrCompleteAProductionTaskForTheSameUserAndDevice() {
        when(proofVerifier.sourceEnvironment()).thenReturn("SANDBOX");
        when(mapper.lockAssignment(7L, "CTA-PROD", "PRODUCTION")).thenReturn(assignment("RUNNING", null, null));

        assertThatThrownBy(() -> service.complete(7L, "CTA-PROD", "sandbox-cross-env", validProof()))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_NOT_FOUND");
        verify(mapper).lockAssignment(7L, "CTA-PROD", "SANDBOX");
        verify(mapper, never()).lockAssignment(7L, "CTA-PROD", "PRODUCTION");
        verify(mapper, never()).creditWallet(any(), any(), any());
    }

    @Test
    void expiryCasCannotMutateTheOtherEnvironmentForTheSameDevice() {
        when(proofVerifier.sourceEnvironment()).thenReturn("SANDBOX");
        var expired = new AppTaskAssignmentMapper.AssignmentRow("CTA-SBX", 11L, "TASK-IG", "Old", "IG",
                "model", "Nexion App", "RUNNING", new BigDecimal("0.2"), 18, 30,
                NOW.minusDays(2), NOW.minusDays(1), null, null, "a".repeat(64), NOW.minusDays(1));
        when(mapper.lockActiveAssignment(7L, 11L, "SANDBOX")).thenReturn(expired);
        when(mapper.expireAssignment(7L, "CTA-SBX", "SANDBOX", NOW)).thenReturn(1);

        service.claim(7L, "sandbox-expiry", new AppTaskClaimRequest(11L));

        verify(mapper).expireAssignment(7L, "CTA-SBX", "SANDBOX", NOW);
        verify(mapper, never()).expireAssignment(7L, "CTA-SBX", "PRODUCTION", NOW);
        verify(mapper, never()).clearRuntimeTask(any(), anyString(), any());
    }

    @Test
    void lockPolicyConsumesS1ProAndRackBoundaries() {
        assertThat(AppTaskAssignmentService.taskLockMinutes(
                device("stellarbox-s1", "S1", "StellarBox S1", 96), lockConfig())).isEqualTo(30);
        assertThat(AppTaskAssignmentService.taskLockMinutes(
                device("stellarbox-pro-v2", "PRO", "StellarBox Pro V2", 192), lockConfig())).isEqualTo(150);
        assertThat(AppTaskAssignmentService.taskLockMinutes(
                device("stellarrack-p2", "RACK", "StellarRack P2", 640), lockConfig())).isEqualTo(480);
    }

    private AppTaskAssignmentMapper.DeviceRow device(
            String type, String tier, String name, int vram) {
        return new AppTaskAssignmentMapper.DeviceRow(11L, "DEV-11", type, tier, name, "ACTIVE",
                NOW.minusDays(1), vram, "SG", "ONLINE", null, false);
    }

    private AppTaskAssignmentMapper.TaskConfigRow task(String id, String type, int minVram, String kill) {
        return new AppTaskAssignmentMapper.TaskConfigRow(id, "Canonical " + type, type, "model-v1",
                new BigDecimal("0.20"), new BigDecimal("0.40"), minVram, "active", kill);
    }

    private AppTaskAssignmentMapper.AssignmentRow assignment(
            String status, LocalDateTime completedAt, String receiptNo) {
        return new AppTaskAssignmentMapper.AssignmentRow("CTA-1", 11L, "TASK-IG", "Canonical IG", "IG",
                "model-v1", "Nexion App", status, new BigDecimal("0.300000"), 18, 30,
                NOW.minusMinutes(1), NOW.plusHours(23), completedAt, receiptNo,
                "a".repeat(64), NOW.plusHours(23));
    }

    private AppTaskCompleteRequest validProof() {
        return new AppTaskCompleteRequest("c".repeat(64), "PRODUCTION", "exec-1",
                "a".repeat(64), 1786363200000L, "d".repeat(64));
    }

    private List<AppTaskAssignmentMapper.ConfigRow> lockConfig() {
        return List.of(new AppTaskAssignmentMapper.ConfigRow("taskLockS1", "30"),
                new AppTaskAssignmentMapper.ConfigRow("taskLockPro", "150"),
                new AppTaskAssignmentMapper.ConfigRow("taskLockRack", "480"));
    }
}
