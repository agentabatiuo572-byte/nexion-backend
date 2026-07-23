package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.BinaryLegAssignmentRow;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.BinarySettlementRow;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.LegVolumeSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BinaryCommissionSettlementServiceTest {
    private static final Long OWNER = 990685L;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 22);

    private BinaryCommissionSettlementMapper mapper;
    private BinarySettlementPolicyProvider policyProvider;
    private TreasuryLedgerPostingFacade ledgerFacade;
    private AuditLogService auditLogService;
    private BinaryCommissionSettlementService service;

    @BeforeEach
    void setUp() {
        mapper = mock(BinaryCommissionSettlementMapper.class);
        policyProvider = mock(BinarySettlementPolicyProvider.class);
        ledgerFacade = mock(TreasuryLedgerPostingFacade.class);
        auditLogService = mock(AuditLogService.class);
        service = new BinaryCommissionSettlementService(
                mapper, policyProvider, ledgerFacade, auditLogService, mock(PlatformConfigFacade.class));

        when(mapper.lockActiveOwner(OWNER)).thenReturn(OWNER);
        when(mapper.ensureSettlementMutex(OWNER, DAY)).thenReturn(1);
        when(mapper.lockSettlementMutex(OWNER, DAY)).thenReturn(1L);
        when(mapper.countDirectMembers(OWNER)).thenReturn(3);
        when(mapper.listAssignmentsForUpdate(OWNER)).thenReturn(List.of(
                new BinaryLegAssignmentRow(OWNER, 990686L, "A"),
                new BinaryLegAssignmentRow(OWNER, 990687L, "B"),
                new BinaryLegAssignmentRow(OWNER, 990688L, "A")));
        when(mapper.listPaidOrderCandidates(eq(OWNER), any(), any())).thenReturn(List.of());
        when(mapper.countReversalRequiredVolumes(OWNER)).thenReturn(0);
        when(mapper.countInvalidPaidOrderVolumes(OWNER)).thenReturn(0);
        when(mapper.monthlyLegVolumes(eq(OWNER), any(), any()))
                .thenReturn(new LegVolumeSnapshot(new BigDecimal("100"), new BigDecimal("80")));
        when(mapper.consumedMatchedInMonth(OWNER, DAY.withDayOfMonth(1)))
                .thenReturn(BigDecimal.ZERO);
        when(mapper.settledAmountOnDate(OWNER, DAY)).thenReturn(BigDecimal.ZERO);
        when(policyProvider.lockPolicy()).thenReturn(policy("10", "0.10", "5000", false));
        when(mapper.insertSettlement(any())).thenReturn(1);
        when(mapper.insertBinaryCommissionEvent(any())).thenReturn(1);
        when(mapper.selectLastInsertId()).thenReturn(88L);
        when(mapper.linkSettlementEvent(OWNER, DAY, 88L)).thenReturn(1);
    }

    @Test
    void assignmentRequiresAnExistingOwnerL1MemberAndIsImmutable() {
        when(mapper.lockDirectMember(OWNER, 990686L)).thenReturn(39L);
        when(mapper.findAssignmentForUpdate(OWNER, 990686L)).thenReturn(null);
        when(mapper.insertAssignment(OWNER, 990686L, "A", 7L, "superadmin")).thenReturn(1);

        BinaryCommissionSettlementService.AssignmentResult result =
                service.assignLeg(OWNER, 990686L, "a", 7L, "superadmin");

        assertThat(result.leg()).isEqualTo("A");
        assertThat(result.replayed()).isFalse();
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getUserId()).isEqualTo(OWNER);
        assertThat(audit.getValue().getActorId()).isEqualTo(7L);
        assertThat(audit.getValue().getActorUsername()).isEqualTo("superadmin");
    }

    @Test
    void assignmentRejectsNonL1AndConflictingReassignment() {
        when(mapper.lockDirectMember(OWNER, 990686L)).thenReturn(null);
        assertThatThrownBy(() -> service.assignLeg(OWNER, 990686L, "A", 7L, "superadmin"))
                .hasMessage("BINARY_MEMBER_NOT_OWNER_L1");

        when(mapper.lockDirectMember(OWNER, 990686L)).thenReturn(39L);
        when(mapper.findAssignmentForUpdate(OWNER, 990686L))
                .thenReturn(new BinaryLegAssignmentRow(OWNER, 990686L, "A"));
        assertThatThrownBy(() -> service.assignLeg(OWNER, 990686L, "B", 7L, "superadmin"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("BINARY_LEG_ASSIGNMENT_IMMUTABLE");
                });
        verify(mapper, never()).insertAssignment(OWNER, 990686L, "B", 7L, "superadmin");
    }

    @Test
    void concurrentConflictingAssignmentAlsoUsesStableHttp409Contract() {
        when(mapper.lockDirectMember(OWNER, 990686L)).thenReturn(39L);
        when(mapper.findAssignmentForUpdate(OWNER, 990686L))
                .thenReturn(null, new BinaryLegAssignmentRow(OWNER, 990686L, "A"));
        when(mapper.insertAssignment(OWNER, 990686L, "B", 7L, "superadmin")).thenReturn(0);

        assertThatThrownBy(() -> service.assignLeg(OWNER, 990686L, "B", 7L, "superadmin"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("BINARY_LEG_ASSIGNMENT_IMMUTABLE");
                });
    }

    @Test
    void thresholdFailureWritesBlockedSnapshotAndNoMoney() {
        when(policyProvider.lockPolicy()).thenReturn(policy("100", "0.10", "5000", false));

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("BINARY_THRESHOLD_NOT_MET");
        assertThat(result.leftVolume()).isEqualByComparingTo("100");
        assertThat(result.rightVolume()).isEqualByComparingTo("80");
        verify(mapper, never()).insertSettlement(any());
        verify(mapper, never()).insertBinaryCommissionEvent(any());
        verify(ledgerFacade, never()).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void transientBlockCanBeFixedAndRetriedSuccessfullyOnTheSameDay() {
        when(policyProvider.lockPolicy()).thenReturn(
                policy("100", "0.10", "5000", false),
                policy("10", "0.10", "5000", false));

        BinaryCommissionSettlementService.SettlementResult blocked = service.settle(OWNER, DAY);
        BinaryCommissionSettlementService.SettlementResult repaired = service.settle(OWNER, DAY);

        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(repaired.status()).isEqualTo("PENDING");
        verify(mapper).insertSettlement(any());
        verify(ledgerFacade).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyBlockedRowIsAtomicallyRemovedUnderMutexAndRetried() {
        when(mapper.findSettlementForUpdate(OWNER, DAY)).thenReturn(new BinarySettlementRow(
                10L, OWNER, DAY, 990686L, 990687L,
                new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "BLOCKED"));
        when(mapper.deleteBlockedSettlement(OWNER, DAY)).thenReturn(1);

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("PENDING");
        verify(mapper).deleteBlockedSettlement(OWNER, DAY);
    }

    @Test
    void settlesIncrementalMatchedVolumeAndCreatesDurablePendingMoneyChain() {
        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.matchedVolume()).isEqualByComparingTo("80.000000");
        assertThat(result.amountUsdt()).isEqualByComparingTo("8.000000");
        assertThat(result.commissionEventId()).isEqualTo(88L);
        verify(mapper).insertBinaryCommissionEvent(any());
        verify(ledgerFacade).postLedgerEntry(
                "F3-BINARY-990685-20260722", OWNER, "TEAM_COMMISSION", "USDT", "IN",
                new BigDecimal("8.000000"), "PENDING", "F3 binary commission cooling payout");
    }

    @Test
    void previousMonthlyConsumptionPreventsCrossDayDoublePayment() {
        when(mapper.consumedMatchedInMonth(OWNER, DAY.withDayOfMonth(1)))
                .thenReturn(new BigDecimal("80"));

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("BINARY_NO_AVAILABLE_MATCHED_VOLUME");
        assertThat(result.matchedVolume()).isZero();
        verify(ledgerFacade, never()).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void laterDaySettledFirstStillPreventsBackdatedDoublePayment() {
        when(mapper.monthlyLegVolumes(eq(OWNER), any(), any()))
                .thenReturn(new LegVolumeSnapshot(new BigDecimal("100"), new BigDecimal("100")));
        when(mapper.consumedMatchedInMonth(OWNER, DAY.withDayOfMonth(1)))
                .thenReturn(new BigDecimal("100"));

        BinaryCommissionSettlementService.SettlementResult backdated = service.settle(OWNER, DAY);

        assertThat(backdated.status()).isEqualTo("BLOCKED");
        assertThat(backdated.reason()).isEqualTo("BINARY_NO_AVAILABLE_MATCHED_VOLUME");
        verify(mapper).consumedMatchedInMonth(OWNER, DAY.withDayOfMonth(1));
        verify(ledgerFacade, never()).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refundedOrderBeforeAnyConsumptionIsVoidedAndExcludedBeforeSettlement() {
        when(mapper.countInvalidPaidOrderVolumes(OWNER)).thenReturn(1);
        when(mapper.voidInvalidUnconsumedPaidOrderVolumes(OWNER)).thenReturn(1);

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("PENDING");
        verify(mapper).voidInvalidUnconsumedPaidOrderVolumes(OWNER);
    }

    @Test
    void refundedOrderAfterPriorConsumptionFailsClosedForManualReversal() {
        when(mapper.countInvalidPaidOrderVolumes(OWNER)).thenReturn(1);
        when(mapper.markInvalidPaidOrderVolumesReversalRequired(OWNER)).thenReturn(1);
        when(mapper.countReversalRequiredVolumes(OWNER)).thenReturn(1);

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("BINARY_REFUND_REVERSAL_REQUIRED");
        verify(mapper).markInvalidPaidOrderVolumesReversalRequired(OWNER);
        verify(ledgerFacade, never()).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void previousMonthRefundBlocksCurrentMonthSettlement() {
        when(mapper.countInvalidPaidOrderVolumes(OWNER)).thenReturn(1);
        when(mapper.markInvalidPaidOrderVolumesReversalRequired(OWNER)).thenReturn(1);
        when(mapper.countReversalRequiredVolumes(OWNER)).thenReturn(1);

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("BINARY_REFUND_REVERSAL_REQUIRED");
        verify(mapper).markInvalidPaidOrderVolumesReversalRequired(OWNER);
        verify(mapper, never()).findSettlementForUpdate(OWNER, DAY);
        verify(ledgerFacade, never()).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refundReconciliationRunsBeforeExistingSettlementReplay() {
        when(mapper.findSettlementForUpdate(OWNER, DAY)).thenReturn(new BinarySettlementRow(
                9L, OWNER, DAY, 990686L, 990687L,
                new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("80"),
                new BigDecimal("8"), new BigDecimal("5000"), 77L, "PENDING"));
        when(mapper.countInvalidPaidOrderVolumes(OWNER)).thenReturn(1);
        when(mapper.markInvalidPaidOrderVolumesReversalRequired(OWNER)).thenReturn(1);
        when(mapper.countReversalRequiredVolumes(OWNER)).thenReturn(1);

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("BINARY_REFUND_REVERSAL_REQUIRED");
        assertThat(result.replayed()).isFalse();
        verify(mapper, never()).findSettlementForUpdate(OWNER, DAY);
    }

    @Test
    void unresolvedReversalRequiredVolumeKeepsEveryRetryFailClosed() {
        when(mapper.countReversalRequiredVolumes(OWNER)).thenReturn(1);

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.reason()).isEqualTo("BINARY_REFUND_REVERSAL_REQUIRED");
        verify(mapper, never()).insertBinaryCommissionEvent(any());
    }

    @Test
    void dailyCapConsumesOnlyPayableMatchedAndLeavesResidualForNextDay() {
        when(policyProvider.lockPolicy()).thenReturn(policy("10", "0.50", "20", false));
        when(mapper.monthlyLegVolumes(eq(OWNER), any(), any()))
                .thenReturn(new LegVolumeSnapshot(new BigDecimal("100"), new BigDecimal("100")));
        when(mapper.consumedMatchedInMonth(OWNER, DAY.withDayOfMonth(1)))
                .thenReturn(new BigDecimal("40"), new BigDecimal("80"));

        BinaryCommissionSettlementService.SettlementResult capped = service.settle(OWNER, DAY);

        assertThat(capped.matchedVolume()).isEqualByComparingTo("40.000000");
        assertThat(capped.amountUsdt()).isEqualByComparingTo("20.000000");

        LocalDate nextDay = DAY.plusDays(1);
        when(mapper.ensureSettlementMutex(OWNER, nextDay)).thenReturn(1);
        when(mapper.lockSettlementMutex(OWNER, nextDay)).thenReturn(2L);
        when(mapper.settledAmountOnDate(OWNER, nextDay)).thenReturn(BigDecimal.ZERO);
        when(mapper.linkSettlementEvent(OWNER, nextDay, 88L)).thenReturn(1);

        BinaryCommissionSettlementService.SettlementResult residual = service.settle(OWNER, nextDay);

        assertThat(residual.matchedVolume()).isEqualByComparingTo("20.000000");
        assertThat(residual.amountUsdt()).isEqualByComparingTo("10.000000");
    }

    @Test
    void rateZeroOrUnreliablePolicyFailsClosedBeforeAnyConsumption() {
        when(policyProvider.lockPolicy())
                .thenThrow(new BinarySettlementPolicyProvider.PolicyBlocked("F3_MATCH_RATE_INVALID"));

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("F3_MATCH_RATE_INVALID");
        verify(mapper, never()).insertBinaryCommissionEvent(any());
        verify(ledgerFacade, never()).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void existingSettlementIsIdempotentAndDoesNotPostAgain() {
        when(mapper.findSettlementForUpdate(OWNER, DAY)).thenReturn(new BinarySettlementRow(
                9L, OWNER, DAY, 990686L, 990687L,
                new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("80"),
                new BigDecimal("8"), new BigDecimal("5000"), 77L, "PENDING"));

        BinaryCommissionSettlementService.SettlementResult result = service.settle(OWNER, DAY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.commissionEventId()).isEqualTo(77L);
        verify(ledgerFacade, never()).postLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void systemAndAdminSettlementEntrypointsAreTransactional() throws Exception {
        var system = BinaryCommissionSettlementService.class
                .getMethod("settle", Long.class, LocalDate.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        var admin = BinaryCommissionSettlementService.class
                .getMethod("settleAsAdmin", Long.class, LocalDate.class, Long.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(system).isNotNull();
        assertThat(admin).isNotNull();
        assertThat(system.rollbackFor()).contains(Exception.class);
        assertThat(admin.rollbackFor()).contains(Exception.class);
        assertThat(system.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
        assertThat(admin.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
    }

    private BinarySettlementPolicyProvider.BinarySettlementPolicy policy(
            String threshold, String rate, String cap, boolean paused) {
        return new BinarySettlementPolicyProvider.BinarySettlementPolicy(
                new BigDecimal(threshold), new BigDecimal(rate), new BigDecimal(cap), paused,
                new BigDecimal("150"), new BigDecimal("120"));
    }
}
