package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;

class EarningsReleaseServiceTest {
    private final EarningsReleaseMapper mapper = mock(EarningsReleaseMapper.class);
    private final RiskReleaseParamsService params = mock(RiskReleaseParamsService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final FundsSandboxProfileGuard sandboxProfile = mock(FundsSandboxProfileGuard.class);
    private final EarningsReleaseService service = new EarningsReleaseService(
            mapper, params, idempotency, audit, sandboxProfile);

    @BeforeEach
    void defaults() {
        when(sandboxProfile.isStrictProductionRuntime()).thenReturn(true);
        when(params.attestationHours()).thenReturn(1);
        when(params.releaseWindowHours()).thenReturn(24);
        when(params.freeSlots()).thenReturn(1);
        when(params.freezeFrom()).thenReturn(3);
        when(params.freeSlotRequiresBinding()).thenReturn(true);
        when(mapper.proofIdentityMatches(any(),eq("JANUS_PRODUCTION_EXECUTOR"))).thenReturn(1);
        when(mapper.consumeAppliedProof(anyString(),any(),anyString(),any(),anyString(),anyString())).thenReturn(1);
        when(params.requireCoverageForAmplifyingRelease()).thenReturn(
                new TreasuryCoverageSnapshot(new BigDecimal("120"), new BigDecimal("105"), true));
    }

    @Test
    void unboundAppReportCannotMintTrustedOnlineSeconds() {
        when(mapper.trustedDeviceBinding(7L, "phone-1")).thenReturn(0);

        service.recordTrustedAttestation(proof("phone-1"));

        verify(mapper, never()).recordAttestation(any(), anyString(), anyString());
        verify(mapper, never()).release(anyString(), anyString());
    }

    @Test
    void clusterQuotaIsLockedBeforeCountingAndReleasing() {
        ProtectedEntry entry = new ProtectedEntry("ER-1", 7L, "K1-C1", "USDT",
                BigDecimal.ONE, "pending_review");
        when(mapper.trustedDeviceBinding(7L, "phone-1")).thenReturn(1);
        when(mapper.recordAttestation(7L, "phone-1", "PRODUCTION")).thenReturn(1);
        when(mapper.attestedSeconds(7L, "PRODUCTION")).thenReturn(3600L);
        when(mapper.protectedEntries(7L, "PRODUCTION")).thenReturn(List.of(entry));
        when(mapper.lockCluster("K1-C1")).thenReturn("K1-C1");
        when(mapper.releasedAccountsInWindow("K1-C1", 7L, 24)).thenReturn(0);
        when(mapper.releaseFromJanusProof("ER-1", "JANUS_PRODUCTION_EXECUTOR")).thenReturn(1);

        service.recordTrustedAttestation(proof("phone-1"));

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockCluster("K1-C1");
        order.verify(mapper).releasedAccountsInWindow("K1-C1", 7L, 24);
        order.verify(mapper).releaseFromJanusProof("ER-1", "JANUS_PRODUCTION_EXECUTOR");
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void deletedClusterFailsClosedBeforeQuotaCountOrRelease() {
        ProtectedEntry entry = new ProtectedEntry("ER-GONE", 7L, "K1-GONE", "USDT",
                BigDecimal.ONE, "pending_review");
        when(mapper.trustedDeviceBinding(7L, "phone-1")).thenReturn(1);
        when(mapper.recordAttestation(7L, "phone-1", "PRODUCTION")).thenReturn(1);
        when(mapper.attestedSeconds(7L, "PRODUCTION")).thenReturn(3600L);
        when(mapper.protectedEntries(7L, "PRODUCTION")).thenReturn(List.of(entry));
        when(mapper.lockCluster("K1-GONE")).thenReturn(null);

        assertThatThrownBy(() -> service.recordTrustedAttestation(proof("phone-1")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .isEqualTo("EARNINGS_RELEASE_CLUSTER_MISSING"));
        verify(mapper, never()).releasedAccountsInWindow(anyString(), any(), anyInt());
        verify(mapper, never()).release(anyString(), anyString());
    }

    private EarningsReleaseService.TrustedAttestationProof proof(String deviceId) {
        return new EarningsReleaseService.TrustedAttestationProof(
                "proof-"+deviceId,7L,deviceId,1L,"ACTIVATED",
                "JANUS_PRODUCTION_EXECUTOR","e".repeat(64));
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

    @Test
    void productionRewardPersistsProductionEnvironmentAndCreditsOnlyProductionWallet() {
        when(mapper.insert(any(EarningsReleaseMapper.EntryWrite.class))).thenReturn(1);
        when(mapper.creditNex(7L, new BigDecimal("5"), "PRODUCTION", 0)).thenReturn(1);

        service.creditReward(7L, "H8_REFERRAL", "REF-1:INVITER:NEX", "NEX",
                new BigDecimal("5"), "PRODUCTION", "idem-h8-production");

        ArgumentCaptor<EarningsReleaseMapper.EntryWrite> entry =
                ArgumentCaptor.forClass(EarningsReleaseMapper.EntryWrite.class);
        verify(mapper).insert(entry.capture());
        assertThat(entry.getValue().sourceType()).isEqualTo("H8_REFERRAL");
        assertThat(entry.getValue().sourceEnvironment()).isEqualTo("PRODUCTION");
        verify(mapper).creditNex(7L, new BigDecimal("5"), "PRODUCTION", 0);
    }

    @Test
    void sandboxCannotBeMislabelledAsProductionH8Reward() {
        assertThatThrownBy(() -> service.creditReward(7L, "H8_REFERRAL", "REF-SBX:INVITER:NEX", "NEX",
                new BigDecimal("5"), "SANDBOX", "idem-h8-sandbox"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("EARNINGS_RELEASE_ENTRY_INVALID");

        verify(mapper, never()).insert(any(EarningsReleaseMapper.EntryWrite.class));
        verify(mapper, never()).creditNex(any(), any(), anyString(), anyInt());
    }

    @Test
    void explicitMockRewardCanOnlyCreditMatchingSandboxWallet() {
        when(mapper.insert(any(EarningsReleaseMapper.EntryWrite.class))).thenReturn(1);
        when(sandboxProfile.isStrictProductionRuntime()).thenReturn(false);
        when(sandboxProfile.isStrictTestRuntime()).thenReturn(true);
        when(mapper.creditNex(7L, new BigDecimal("5"), "SANDBOX", 1)).thenReturn(1);

        service.creditReward(7L, "MOCK_REFERRAL", "REF-SBX:INVITER:NEX", "NEX",
                new BigDecimal("5"), "SANDBOX", "idem-h8-sandbox-mock");

        ArgumentCaptor<EarningsReleaseMapper.EntryWrite> entry =
                ArgumentCaptor.forClass(EarningsReleaseMapper.EntryWrite.class);
        verify(mapper).insert(entry.capture());
        assertThat(entry.getValue().sourceEnvironment()).isEqualTo("SANDBOX");
        verify(mapper).creditNex(7L, new BigDecimal("5"), "SANDBOX", 1);
    }

    @Test
    void matchingDurableRewardSourceIsReplayedWithoutASecondWalletCredit() {
        when(sandboxProfile.isStrictProductionRuntime()).thenReturn(false);
        when(sandboxProfile.isStrictTestRuntime()).thenReturn(true);
        when(mapper.insert(any(EarningsReleaseMapper.EntryWrite.class))).thenReturn(0);
        when(mapper.findBySource("MOCK_LEARNING_REWARD", "LEARN:7:h3-live:v1", 7L))
                .thenReturn(new EarningsReleaseMapper.ExistingEntry(
                        "ER-LEARNING-EXISTING", 7L, "MOCK_LEARNING_REWARD", "LEARN:7:h3-live:v1",
                        "NEX", new BigDecimal("5"), "ACTIVE", "LEARN:7:h3-live:v1:NEX", "SANDBOX", 0));

        String entryNo = service.creditReward(7L, "MOCK_LEARNING_REWARD", "LEARN:7:h3-live:v1", "NEX",
                new BigDecimal("5"), "SANDBOX", "LEARN:7:h3-live:v1:NEX");

        assertThat(entryNo).isEqualTo("ER-LEARNING-EXISTING");
        verify(mapper, never()).creditNex(any(), any(), anyString(), anyInt());
    }

    @Test
    void mismatchedDurableRewardSourceFailsClosedRatherThanMaskingTheConflict() {
        when(sandboxProfile.isStrictProductionRuntime()).thenReturn(false);
        when(sandboxProfile.isStrictTestRuntime()).thenReturn(true);
        when(mapper.insert(any(EarningsReleaseMapper.EntryWrite.class))).thenReturn(0);
        when(mapper.findBySource("MOCK_LEARNING_REWARD", "LEARN:7:h3-live:v1", 7L))
                .thenReturn(new EarningsReleaseMapper.ExistingEntry(
                        "ER-LEARNING-CONFLICT", 7L, "MOCK_LEARNING_REWARD", "LEARN:7:h3-live:v1",
                        "NEX", new BigDecimal("4"), "ACTIVE", "LEARN:7:h3-live:v1:NEX", "SANDBOX", 0));

        assertThatThrownBy(() -> service.creditReward(7L, "MOCK_LEARNING_REWARD", "LEARN:7:h3-live:v1",
                "NEX", new BigDecimal("5"), "SANDBOX", "LEARN:7:h3-live:v1:NEX"))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("EARNINGS_RELEASE_ENTRY_CONFLICT"));

        verify(mapper, never()).creditNex(any(), any(), anyString(), anyInt());
    }

    @Test
    void developmentCanonicalRewardCreditsPhysicalDevelopmentWallet() {
        when(sandboxProfile.isStrictProductionRuntime()).thenReturn(true);
        when(sandboxProfile.isStrictDevelopmentRuntime()).thenReturn(true);
        when(mapper.insert(any(EarningsReleaseMapper.EntryWrite.class))).thenReturn(1);
        when(mapper.creditNex(7L, new BigDecimal("10"), "PRODUCTION", 0)).thenReturn(1);

        service.creditReward(7L, "LEARNING_REWARD", "LEARN:7:h3-live:v1", "NEX",
                new BigDecimal("10"), "PRODUCTION", "LEARN:7:h3-live:v1:NEX");

        verify(mapper).creditNex(7L, new BigDecimal("10"), "PRODUCTION", 0);
    }

    @Test
    void environmentAndActiveProfileMustMatchExactly() {
        when(sandboxProfile.isStrictProductionRuntime()).thenReturn(true);
        when(sandboxProfile.isStrictDevelopmentRuntime()).thenReturn(true);
        assertThatThrownBy(() -> service.creditReward(7L, "MOCK_LEARNING_REWARD", "dev-sandbox", "NEX",
                BigDecimal.ONE, "SANDBOX", "dev-sandbox-key"))
                .isInstanceOf(BizException.class).hasMessage("EARNINGS_RELEASE_ENVIRONMENT_INVALID");

        when(sandboxProfile.isStrictDevelopmentRuntime()).thenReturn(false);
        when(sandboxProfile.isStrictProductionRuntime()).thenReturn(false);
        when(sandboxProfile.isStrictTestRuntime()).thenReturn(true);
        assertThatThrownBy(() -> service.creditReward(7L, "LEARNING_REWARD", "test-production", "NEX",
                BigDecimal.ONE, "PRODUCTION", "test-production-key"))
                .isInstanceOf(BizException.class).hasMessage("EARNINGS_RELEASE_ENVIRONMENT_INVALID");

        when(sandboxProfile.isStrictTestRuntime()).thenReturn(false);
        assertThatThrownBy(() -> service.creditReward(7L, "LEARNING_REWARD", "unknown-profile", "NEX",
                BigDecimal.ONE, "PRODUCTION", "unknown-profile-key"))
                .isInstanceOf(BizException.class).hasMessage("EARNINGS_RELEASE_PROFILE_INVALID");
    }
}
