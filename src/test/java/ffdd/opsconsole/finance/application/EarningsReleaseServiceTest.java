package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.ProtectedEntry;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.RiskCluster;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.risk.dto.EarningsManualReleaseRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class EarningsReleaseServiceTest {
    private final EarningsReleaseMapper mapper = mock(EarningsReleaseMapper.class);
    private final RiskReleaseParamsService params = mock(RiskReleaseParamsService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EarningsReleaseService service = new EarningsReleaseService(mapper, params, idempotency, audit);

    @BeforeEach
    void defaults() {
        when(params.attestationHours()).thenReturn(1);
        when(params.releaseWindowHours()).thenReturn(24);
        when(params.freeSlots()).thenReturn(1);
        when(params.freezeFrom()).thenReturn(3);
        when(params.freeSlotRequiresBinding()).thenReturn(true);
        when(params.requireCoverageForAmplifyingRelease()).thenReturn(
                new TreasuryCoverageSnapshot(new BigDecimal("120"), new BigDecimal("105"), true));
    }

    @Test
    void unboundAppReportCannotMintTrustedOnlineSeconds() {
        when(mapper.trustedDeviceBinding(7L, "phone-1")).thenReturn(0);

        service.recordTrustedAttestation(7L, "phone-1");

        verify(mapper, never()).recordAttestation(any(), anyString());
        verify(mapper, never()).release(anyString(), anyString());
    }

    @Test
    void clusterQuotaIsLockedBeforeCountingAndReleasing() {
        ProtectedEntry entry = new ProtectedEntry("ER-1", 7L, "K1-C1", "USDT",
                BigDecimal.ONE, "pending_review");
        when(mapper.trustedDeviceBinding(7L, "phone-1")).thenReturn(1);
        when(mapper.recordAttestation(7L, "phone-1")).thenReturn(1);
        when(mapper.attestedSeconds(7L)).thenReturn(3600L);
        when(mapper.protectedEntries(7L)).thenReturn(List.of(entry));
        when(mapper.lockCluster("K1-C1")).thenReturn("K1-C1");
        when(mapper.releasedAccountsInWindow("K1-C1", 7L, 24)).thenReturn(0);
        when(mapper.release("ER-1", "attest")).thenReturn(1);

        service.recordTrustedAttestation(7L, "phone-1");

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockCluster("K1-C1");
        order.verify(mapper).releasedAccountsInWindow("K1-C1", 7L, 24);
        order.verify(mapper).release("ER-1", "attest");
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void deletedClusterFailsClosedBeforeQuotaCountOrRelease() {
        ProtectedEntry entry = new ProtectedEntry("ER-GONE", 7L, "K1-GONE", "USDT",
                BigDecimal.ONE, "pending_review");
        when(mapper.trustedDeviceBinding(7L, "phone-1")).thenReturn(1);
        when(mapper.recordAttestation(7L, "phone-1")).thenReturn(1);
        when(mapper.attestedSeconds(7L)).thenReturn(3600L);
        when(mapper.protectedEntries(7L)).thenReturn(List.of(entry));
        when(mapper.lockCluster("K1-GONE")).thenReturn(null);

        assertThatThrownBy(() -> service.recordTrustedAttestation(7L, "phone-1"))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .isEqualTo("EARNINGS_RELEASE_CLUSTER_MISSING"));
        verify(mapper, never()).releasedAccountsInWindow(anyString(), any(), anyInt());
        verify(mapper, never()).release(anyString(), anyString());
    }

    @Test
    void manualReleaseIsAuditedAndMovesExactlyOneProtectedEntry() {
        ProtectedEntry entry = new ProtectedEntry("ER-2", 8L, "K1-C2", "NEX",
                new BigDecimal("5"), "bonus_locked");
        when(mapper.lockProtectedEntry("ER-2")).thenReturn(entry);
        when(mapper.release("ER-2", "manual")).thenReturn(1);

        service.manualReleaseOnce("ER-2", new EarningsManualReleaseRequest(
                "manual investigation completed", "superadmin"));

        verify(mapper).release("ER-2", "manual");
        verify(audit).recordRequired(any());
    }

    @Test
    void manualReleaseFailsClosedWhenB1CoverageGateRejectsOutflow() {
        ProtectedEntry entry = new ProtectedEntry("ER-BLOCKED", 8L, "K1-C2", "USDT",
                new BigDecimal("5"), "bonus_locked");
        when(mapper.lockProtectedEntry("ER-BLOCKED")).thenReturn(entry);
        when(params.requireCoverageForAmplifyingRelease())
                .thenThrow(new BizException(422, "COVERAGE_BELOW_REDLINE"));

        assertThatThrownBy(() -> service.manualReleaseOnce("ER-BLOCKED",
                new EarningsManualReleaseRequest("coverage must block this release", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .isEqualTo("COVERAGE_BELOW_REDLINE"));
        verify(mapper, never()).release(anyString(), anyString());
    }

    @Test
    void frozenClusterBlocksWithdrawalEvenForPreviouslyWithdrawableEntries() {
        when(mapper.riskCluster(9L)).thenReturn(new RiskCluster("K1-C3", 2, "flagged"));

        assertThatThrownBy(() -> service.assertWithdrawable(9L, new BigDecimal("100"), BigDecimal.ONE))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .isEqualTo("WITHDRAWAL_CLUSTER_RESTRICTED"));
    }
}
