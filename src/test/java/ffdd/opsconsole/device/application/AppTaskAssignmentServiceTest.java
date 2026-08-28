package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

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
import org.springframework.core.env.Environment;

class AppTaskAssignmentServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 12, 0);
    private final AppTaskAssignmentMapper mapper = mock(AppTaskAssignmentMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ComputeTaskProofVerifier proofVerifier = mock(ComputeTaskProofVerifier.class);
    private final Environment environment = mock(Environment.class);
    private final AppTaskAssignmentService service = new AppTaskAssignmentService(
            mapper, idempotency, outbox, audit, proofVerifier,
            environment,
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(0));
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(mapper.lockOwnedDevice(7L, 11L)).thenReturn(device("stellarbox-s1", "S1", "StellarBox S1", 96));
        when(mapper.eligibleTasks(96)).thenReturn(List.of(task("TASK-IG", "IG", 24, "pending")));
        when(mapper.taskLockConfig()).thenReturn(lockConfig());
        when(mapper.e3CapacityConfig()).thenReturn(capacityConfig());
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
        assertThat(result.getData().source()).isEqualTo("server");
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().serverCanonical()).isTrue();
        verify(mapper).insertAssignment(anyString(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(30), anyString(), any(), anyString(), any(), any());
        verify(mapper).bindRuntimeTask(any(), anyString(), any(), any());
        verify(audit).recordRequired(any());
    }

    @Test
    void cloudShareUsesManagedEightGbRoutingCapabilityInsteadOfPhysicalVram() {
        when(mapper.lockOwnedDevice(7L, 11L)).thenReturn(
                device("SHARE", "SHARE", "Cloud Share", 0));
        when(mapper.eligibleTasks(8)).thenReturn(List.of(task("TASK-EM", "EM", 8, "pending")));

        var result = service.claim(7L, "claim-cloud-share", new AppTaskClaimRequest(11L));

        assertThat(result.getData().taskId()).isEqualTo("TASK-EM");
        verify(mapper).eligibleTasks(8);
        verify(mapper, never()).eligibleTasks(0);
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
    void anyInvalidActiveE2MinimumVramFailsTheWholeNewAssignmentClosed() {
        when(mapper.eligibleTasks(96)).thenReturn(List.of(
                task("TASK-IG", "IG", 24, "pending"),
                new AppTaskAssignmentMapper.TaskConfigRow(
                        "TASK-BROKEN", "Broken", "EM", "model-v1",
                        new BigDecimal("0.20"), new BigDecimal("0.40"), null,
                        "active", "pending")));

        assertThatThrownBy(() -> service.claim(7L, "claim-invalid-e2", new AppTaskClaimRequest(11L)))
                .isInstanceOf(BizException.class).hasMessageContaining("E2_TASK_CONFIG_INVALID");
        verify(mapper, never()).insertAssignment(anyString(), any(), any(), any(), any(), any(), any(),
                anyString(), any(), anyString(), any(), any());
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
        when(mapper.creditWallet(any(), any(), any(), any())).thenReturn(1);
        when(mapper.walletUsdt(7L)).thenReturn(new BigDecimal("10.300000"));
        when(mapper.insertWalletLedger(any(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.insertEarningEvent(anyString(), any(), any(), anyString(), any(), any())).thenReturn(1);
        when(mapper.completeAssignment(any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.userEventAttribution(7L)).thenReturn(
                new AppTaskAssignmentMapper.UserEventAttribution("P3", 8, "2026-W30"));

        var proof = validProof();
        var result = service.complete(7L, "CTA-1", "complete-a", proof);
        assertThat(result.getData().source()).isEqualTo("server");
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().serverCanonical()).isTrue();
        assertThatThrownBy(() -> service.complete(7L, "CTA-1", "complete-b", proof))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_PROOF_REPLAYED");

        verify(mapper, times(1)).insertReceipt(any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        verify(mapper, times(1)).creditWallet(any(), any(), any(), any());
        verify(mapper, times(1)).insertWalletLedger(any(), any(), anyString(), any(), any(), any());
        verify(mapper, times(1)).upsertDeviceTaskLock(any(), any(), anyString(), any(), anyString(), any());
        verify(outbox, times(2)).publishUserEvent(anyString(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), any());
    }

    @Test
    void sandboxCompletionIsUnavailableWithoutRunScopedTaskOrDeviceProjection() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        assertThatThrownBy(() -> service.complete(7L, "CTA-1", "sandbox-complete", validProof()))
                .hasMessage("TASK_ASSIGNMENT_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(7L);
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void completionRechecksLiveKillAndVramBeforeProofOrWalletMutation() {
        when(mapper.lockAssignment(7L, "CTA-1", "PRODUCTION")).thenReturn(assignment("RUNNING", null, null));
        when(mapper.taskRuntimeGate(7L, 11L, "TASK-IG"))
                .thenReturn(new AppTaskAssignmentMapper.TaskRuntimeGateRow("active", "已 kill", 192, 96));

        assertThatThrownBy(() -> service.complete(7L, "CTA-1", "killed-complete", validProof()))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_KILLED");
        verify(proofVerifier, never()).verify(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any());
        verify(mapper, never()).creditWallet(any(), any(), any(), any());
    }

    @Test
    void deviceDeactivatedAfterClaimIsRejectedBeforeReceiptOrRewardMutation() {
        when(mapper.lockAssignment(7L, "CTA-1", "PRODUCTION"))
                .thenReturn(assignment("RUNNING", null, null));
        when(mapper.lockOwnedDevice(7L, 11L)).thenReturn(null);

        assertThatThrownBy(() -> service.complete(7L, "CTA-1", "deactivated-after-claim", validProof()))
                .isInstanceOf(BizException.class).hasMessageContaining("TASK_ASSIGNMENT_DEVICE_NOT_FOUND");
        verify(mapper, never()).insertReceipt(any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        verify(mapper, never()).creditWallet(any(), any(), any(), any());
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
        verify(mapper).clearRuntimeTask(7L, 11L, "CTA-OLD", NOW);
        verify(mapper).insertAssignment(anyString(), any(), any(), any(), any(), any(), any(),
                anyString(), any(), anyString(), any(), any());
    }

    @Test
    void sandboxCannotReadProductionTaskBecauseRuntimeIsUnavailable() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});

        assertThatThrownBy(() -> service.complete(7L, "CTA-PROD", "sandbox-cross-env", validProof()))
                .isInstanceOf(BizException.class).hasMessage("TASK_ASSIGNMENT_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).lockAssignment(anyLong(), anyString(), anyString());
    }

    @Test
    void sandboxCannotMutateDeviceLockBecauseRuntimeIsUnavailable() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        assertThatThrownBy(() -> service.claim(7L, "sandbox-expiry", new AppTaskClaimRequest(11L)))
                .hasMessage("TASK_ASSIGNMENT_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).lockDeviceTaskLock(anyLong(), anyLong(), anyString());
    }

    @Test
    void mixedRuntimeIsRejectedBeforeAnyTaskAssignmentReadOrWrite() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod", "dev"});

        assertThatThrownBy(() -> service.assignments(7L))
                .hasMessage("TASK_ASSIGNMENT_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).assignments(anyLong(), anyString());
        verify(mapper, never()).ownedDevices(anyLong());
    }

    @Test
    void assignmentsExposeCanonicalProductionProvenance() {
        when(mapper.assignments(7L, "PRODUCTION")).thenReturn(List.of());
        when(mapper.ownedDevices(7L)).thenReturn(List.of());

        var result = service.assignments(7L);

        assertThat(result.getData().source()).isEqualTo("server");
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().serverCanonical()).isTrue();
    }

    @Test
    void developmentReadsEveryActiveDevelopmentAccountsProductionShapedDevicesAndSettlementHistory() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(1));
        when(mapper.developmentOwnedDevices(7L))
                .thenReturn(List.of(device("phone", "PHONE", "你的手机", 8)));
        when(mapper.developmentAssignments(7L, "PRODUCTION"))
                .thenReturn(List.of(new AppTaskAssignmentMapper.AssignmentRow(
                        "CTA-1", 11L, null, "Development settled compute task", "LLM_INFERENCE",
                        "gemma4-e4b-ctx32k", "Gemma AI Support", "COMPLETED", new BigDecimal("68.40"),
                        45, 0, NOW.minusMinutes(1), null, NOW, "CTR-1", null, null)));

        var result = service.assignments(7L);

        assertThat(result.getData().source()).isEqualTo("server");
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().serverCanonical()).isTrue();
        assertThat(result.getData().devices()).hasSize(1);
        assertThat(result.getData().devices().get(0).currentTask()).isNull();
        assertThat(result.getData().devices().get(0).recentTasks()).hasSize(1);
        assertThat(result.getData().devices().get(0).recentTasks().get(0).receiptNo()).isEqualTo("CTR-1");
        assertThat(result.getData().devices().get(0).recentTasks().get(0).taskClass()).isEqualTo("LL");
        verify(mapper, never()).ownedDevices(anyLong());
        verify(mapper, never()).assignments(anyLong(), anyString());
        verify(mapper, never()).sandboxOwnedDevices(anyLong(), anyString());
    }

    @Test
    void completionUsesTheRewardFrozenAtClaimForReceiptWalletLedgerAndEvents() {
        BigDecimal adjustedReward = new BigDecimal("0.257374");
        var running = assignment("RUNNING", null, null, adjustedReward);
        when(mapper.lockAssignment(7L, "CTA-1", "PRODUCTION")).thenReturn(running);
        when(mapper.deviceInstanceNo(7L, 11L)).thenReturn("DEV-11");
        when(proofVerifier.verify(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new ComputeTaskProofVerifier.Verification(false, "b".repeat(64)));
        when(mapper.insertReceipt(any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(mapper.creditWallet(any(), any(), any(), any())).thenReturn(1);
        when(mapper.walletUsdt(7L)).thenReturn(new BigDecimal("10.257374"));
        when(mapper.insertWalletLedger(any(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.insertEarningEvent(anyString(), any(), any(), anyString(), any(), any())).thenReturn(1);
        when(mapper.completeAssignment(any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.userEventAttribution(7L)).thenReturn(
                new AppTaskAssignmentMapper.UserEventAttribution("P3", 8, "2026-W30"));

        var result = service.complete(7L, "CTA-1", "complete-adjusted", validProof());

        assertThat(result.getData().rewardUsdt()).isEqualByComparingTo(adjustedReward);
        verify(mapper).insertReceipt(eq(7L), eq(11L), eq(running), anyString(), anyString(),
                eq("CREDITED"), eq("PRODUCTION"), eq(NOW));
        verify(mapper).creditWallet(7L, 11L, adjustedReward, NOW);
        verify(mapper).insertWalletLedger(7L, 11L, "CTA-1", adjustedReward,
                new BigDecimal("10.257374"), NOW);
        verify(mapper).insertEarningEvent(anyString(), eq(7L), eq(11L), anyString(),
                eq(adjustedReward), eq(NOW));
        verify(mapper, never()).e3CapacityConfig();
    }

    @Test
    void claimFreezesTheServerE3CapacityAdjustedRewardForReceiptAndWalletSettlement() {
        when(mapper.lockOwnedDevice(7L, 11L)).thenReturn(
                device("stellarbox-pro", "PRO", "StellarBox Pro", 192, null, NOW.minusMonths(4)));
        when(mapper.eligibleTasks(192)).thenReturn(List.of(task("TASK-IG", "IG", 24, "pending")));

        var claimed = service.claim(7L, "claim-aged-pro", new AppTaskClaimRequest(11L));

        assertThat(claimed.getData().rewardUsdt()).isEqualByComparingTo("0.257374");
        verify(mapper).insertAssignment(anyString(), eq(7L), eq(11L), any(),
                eq(new BigDecimal("0.257374")), eq(18), eq(150), anyString(), any(),
                eq("PRODUCTION"), eq(NOW), any());
    }

    @Test
    void claimFailsClosedBeforeCreatingATaskWhenAnyRequiredE3ConfigKeyIsMissing() {
        when(mapper.e3CapacityConfig()).thenReturn(capacityConfig().stream()
                .filter(row -> !"cycleMonths".equals(row.configKey()))
                .toList());

        assertThatThrownBy(() -> service.claim(7L, "claim-missing-e3", new AppTaskClaimRequest(11L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("E3_CAPACITY_CONFIG_INVALID");

        verify(mapper, never()).insertAssignment(anyString(), any(), any(), any(), any(), any(), any(),
                anyString(), any(), anyString(), any(), any());
    }

    @Test
    void developmentReadsTheActiveAccountsCanonicalComputeReceipt() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(1));
        when(mapper.developmentReceipt(7L, "R-CTA-1")).thenReturn(
                new AppTaskAssignmentMapper.ReceiptRow(
                        "R-CTA-1", "CTA-1", 11L, "DEV-11", "你的手机", "MOBILE", "Adreno",
                        8, "TASK-LL", "Development settled compute task", "LLM_INFERENCE",
                        "gemma4-e4b-ctx32k", "Gemma AI Support", new BigDecimal("68.400000"),
                        BigDecimal.ZERO, "SETTLED", "a".repeat(64), NOW.minusSeconds(45), NOW, 45));

        var result = service.receipt(7L, "R-CTA-1");

        assertThat(result.getData().receiptNo()).isEqualTo("R-CTA-1");
        assertThat(result.getData().proofHash()).isEqualTo("a".repeat(64));
        assertThat(result.getData().deviceName()).isEqualTo("你的手机");
        assertThat(result.getData().durationSec()).isEqualTo(45);
        assertThat(result.getData().source()).isEqualTo("server");
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().serverCanonical()).isTrue();
        verify(mapper, never()).receipt(anyLong(), anyString());
    }

    @Test
    void developmentPaginatesEveryCanonicalComputeReceiptBeyondTheAssignmentPreview() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(1));
        when(mapper.developmentReceipts(7L, 0, 3)).thenReturn(List.of(
                receiptRow("R-CTA-3", "CTA-3", NOW),
                receiptRow("R-CTA-2", "CTA-2", NOW.minusMinutes(1)),
                receiptRow("R-CTA-1", "CTA-1", NOW.minusMinutes(2))));

        var result = service.receipts(7L, 0, 2);

        assertThat(result.getData().items()).extracting("receiptNo")
                .containsExactly("R-CTA-3", "R-CTA-2");
        assertThat(result.getData().nextOffset()).isEqualTo(2);
        assertThat(result.getData().source()).isEqualTo("server");
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().serverCanonical()).isTrue();
        verify(mapper).developmentReceipts(7L, 0, 3);
        verify(mapper, never()).receipts(anyLong(), anyInt(), anyInt());
    }

    @Test
    void receiptPaginationRejectsInvalidBoundsBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.receipts(7L, -1, 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TASK_RECEIPT_PAGE_INVALID");
        assertThatThrownBy(() -> service.receipts(7L, 0, 51))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TASK_RECEIPT_PAGE_INVALID");
        verify(mapper, never()).receipts(anyLong(), anyInt(), anyInt());
        verify(mapper, never()).developmentReceipts(anyLong(), anyInt(), anyInt());
    }

    @Test
    void productionCannotReadAnotherUsersComputeReceipt() {
        when(mapper.receipt(7L, "R-OTHER-USER")).thenReturn(null);

        assertThatThrownBy(() -> service.receipt(7L, "R-OTHER-USER"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TASK_RECEIPT_NOT_FOUND");
        verify(mapper).receipt(7L, "R-OTHER-USER");
    }

    @Test
    void developmentRejectsAnUnsettledComputeReceipt() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(1));
        when(mapper.developmentReceipt(7L, "R-PENDING-1")).thenReturn(
                new AppTaskAssignmentMapper.ReceiptRow(
                        "R-PENDING-1", "PENDING-1", 11L, "DEV-11", "你的手机", "MOBILE", "Adreno",
                        8, "TASK-LL", "Pending compute task", "LL", "gemma4-e4b-ctx32k",
                        "Gemma AI Support", new BigDecimal("0.250000"), BigDecimal.ZERO,
                        "PENDING", "b".repeat(64), NOW.minusSeconds(45), NOW, 45));

        assertThatThrownBy(() -> service.receipt(7L, "R-PENDING-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TASK_RECEIPT_DATA_INVALID");
    }

    @Test
    void developmentRejectsAComputeReceiptWithMissingReward() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(1));
        when(mapper.developmentReceipt(7L, "R-MISSING-REWARD-1")).thenReturn(
                new AppTaskAssignmentMapper.ReceiptRow(
                        "R-MISSING-REWARD-1", "MISSING-REWARD-1", 11L, "DEV-11", "你的手机",
                        "MOBILE", "Adreno", 8, "TASK-LL", "Completed compute task", "LL",
                        "gemma4-e4b-ctx32k", "Gemma AI Support", null, BigDecimal.ZERO,
                        "SETTLED", "c".repeat(64), NOW.minusSeconds(45), NOW, 45));

        assertThatThrownBy(() -> service.receipt(7L, "R-MISSING-REWARD-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TASK_RECEIPT_DATA_INVALID");
    }

    @Test
    void acceptanceSandboxReadsOnlyItsRunScopedDevices() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", ""))
                .thenReturn("phone-activation-e2e-20260817");
        when(environment.getProperty("nexion.wheel.sandbox.run-id", "phone-activation-e2e-20260817"))
                .thenReturn("phone-activation-e2e-20260817");
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(1));
        when(mapper.sandboxOwnedDevices(7L, "phone-activation-e2e-20260817"))
                .thenReturn(List.of(device("phone", "PHONE", "你的手机", 8)));

        var result = service.assignments(7L);

        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().devices()).hasSize(1);
        assertThat(result.getData().devices().get(0).recentTasks()).isEmpty();
        verify(mapper, never()).developmentOwnedDevices(anyLong());
        verify(mapper, never()).developmentAssignments(anyLong(), anyString());
    }

    @Test
    void localSandboxRejectsReservedLegacyRunBeforeReadingAnyDevice() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", ""))
                .thenReturn("LEGACY_UNSCOPED");
        when(environment.getProperty("nexion.wheel.sandbox.run-id", "LEGACY_UNSCOPED"))
                .thenReturn("LEGACY_UNSCOPED");
        when(mapper.userScope(7L)).thenReturn(new AppTaskAssignmentMapper.UserScope(1));

        assertThatThrownBy(() -> service.assignments(7L))
                .hasMessage("TASK_ASSIGNMENT_SANDBOX_RUN_ID_REQUIRED");
        verify(mapper, never()).sandboxOwnedDevices(anyLong(), anyString());
    }

    @Test
    void unknownRuntimeIsRejectedBeforeAnyTaskAssignmentReadOrWrite() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"staging"});

        assertThatThrownBy(() -> service.claim(7L, "unknown", new AppTaskClaimRequest(11L)))
                .hasMessage("TASK_ASSIGNMENT_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).lockOwnedDevice(anyLong(), anyLong());
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void productionRequiresProductionProofEnvironmentBeforeIdempotency() {
        when(proofVerifier.sourceEnvironment()).thenReturn("SANDBOX");

        assertThatThrownBy(() -> service.claim(7L, "mismatch", new AppTaskClaimRequest(11L)))
                .hasMessage("TASK_ASSIGNMENT_SOURCE_ENVIRONMENT_INVALID");
        verify(mapper).userScope(7L);
        verify(mapper, never()).lockOwnedDevice(anyLong(), anyLong());
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
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
        return device(type, tier, name, vram, NOW.minusDays(1));
    }

    private AppTaskAssignmentMapper.DeviceRow device(
            String type, String tier, String name, int vram, LocalDateTime purchasedAt) {
        return device(type, tier, name, vram, purchasedAt, NOW.minusDays(1));
    }

    private AppTaskAssignmentMapper.DeviceRow device(
            String type, String tier, String name, int vram,
            LocalDateTime purchasedAt, LocalDateTime activatedAt) {
        return new AppTaskAssignmentMapper.DeviceRow(11L, "DEV-11", type, tier, name, "ACTIVE",
                type, purchasedAt, activatedAt, vram, "SG", "ONLINE", null, false);
    }

    private AppTaskAssignmentMapper.TaskConfigRow task(String id, String type, int minVram, String kill) {
        return new AppTaskAssignmentMapper.TaskConfigRow(id, "Canonical " + type, type, "model-v1",
                new BigDecimal("0.20"), new BigDecimal("0.40"), minVram, "active", kill);
    }

    private AppTaskAssignmentMapper.AssignmentRow assignment(
            String status, LocalDateTime completedAt, String receiptNo) {
        return assignment(status, completedAt, receiptNo, new BigDecimal("0.300000"));
    }

    private AppTaskAssignmentMapper.AssignmentRow assignment(
            String status, LocalDateTime completedAt, String receiptNo, BigDecimal reward) {
        return new AppTaskAssignmentMapper.AssignmentRow("CTA-1", 11L, "TASK-IG", "Canonical IG", "IG",
                "model-v1", "Nexion App", status, reward, 18, 30,
                NOW.minusMinutes(1), NOW.plusHours(23), completedAt, receiptNo,
                "a".repeat(64), NOW.plusHours(23));
    }

    private AppTaskAssignmentMapper.ReceiptRow receiptRow(
            String receiptNo, String taskNo, LocalDateTime completedAt) {
        return new AppTaskAssignmentMapper.ReceiptRow(
                receiptNo, taskNo, 11L, "DEV-11", "你的手机", "MOBILE", "Adreno",
                8, "TASK-LL", "Development settled compute task", "LLM_INFERENCE",
                "gemma4-e4b-ctx32k", "Gemma AI Support", new BigDecimal("0.250000"),
                BigDecimal.ZERO, "SETTLED", "a".repeat(64), completedAt.minusSeconds(45),
                completedAt, 45);
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

    private List<AppTaskAssignmentMapper.ConfigRow> capacityConfig() {
        return java.util.Map.ofEntries(
                        java.util.Map.entry("capacityBand1DeltaPct", "-3"),
                        java.util.Map.entry("capacityBand2DeltaPct", "-6"),
                        java.util.Map.entry("capacityBand3DeltaPct", "-23.7"),
                        java.util.Map.entry("stageEarlyEnd", "3"),
                        java.util.Map.entry("stageMidEnd", "8"),
                        java.util.Map.entry("cycleMonths", "13"),
                        java.util.Map.entry("capacityFloorPct", "22"),
                        java.util.Map.entry("capacitySubsidyDays", "30"),
                        java.util.Map.entry("taskLockS1", "30"),
                        java.util.Map.entry("taskLockPro", "150"),
                        java.util.Map.entry("taskLockRack", "480"),
                        java.util.Map.entry("capacityApplyToPhone", "false"),
                        java.util.Map.entry("capacityApplyToCloudShare", "false"),
                        java.util.Map.entry("capacityApplyToPcGpu", "false"),
                        java.util.Map.entry("capacityApplyToS1", "true"),
                        java.util.Map.entry("capacityApplyToPro", "true"),
                        java.util.Map.entry("capacityApplyToProV2", "true"),
                        java.util.Map.entry("capacityApplyToRackP1", "true"),
                        java.util.Map.entry("capacityApplyToRackP2", "true"))
                .entrySet().stream()
                .map(entry -> new AppTaskAssignmentMapper.ConfigRow(entry.getKey(), entry.getValue()))
                .toList();
    }
}
