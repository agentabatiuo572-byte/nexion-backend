package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import ffdd.opsconsole.growth.dto.ReferralSettlementRunRequest;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class OpsReferralRewardServiceTest {
    private final ReferralRewardMapper mapper = mock(ReferralRewardMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy = mock(OpsReadTimeSeedPolicy.class);
    private final OpsReferralRewardService service = new OpsReferralRewardService(
            mapper, config, ledger, audit, idempotency, coverage, outbox, readTimeSeedPolicy);

    @BeforeEach
    void setUp() {
        when(mapper.lockRewardMutation()).thenReturn("H8_REWARD");
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(Map.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(config.activeValue("K.rewards.welcomeGift.usdtAmount")).thenReturn(Optional.of("5"));
        when(config.activeValue("K.rewards.welcomeGift.nexAmount")).thenReturn(Optional.of("20"));
        when(config.activeValue("K.rewards.welcomeGift.lockMode")).thenReturn(Optional.of("risk_bucket"));
        when(config.activeValue("K.rewards.inviterReward.nexAmount")).thenReturn(Optional.of("10"));
        when(config.activeValue("K.rewards.referral.effectiveAt")).thenReturn(Optional.of("2026-07-17T00:00:00Z"));
        when(config.activeValue("H1.rhythm.totalMonths")).thenReturn(Optional.of("12"));
        when(config.activeValue("H1.rhythm.currentMonth")).thenReturn(Optional.of("7"));
        when(config.activeValue("growth.phase.current")).thenReturn(Optional.of("P4"));
        when(config.activeValue("growth.phase.month.7.newUserBonusMultiplier")).thenReturn(Optional.of("1"));
        when(config.activeValue("growth.phase.month.7.inviteRewardMultiplier")).thenReturn(Optional.of("1"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("100"), new BigDecimal("85")));
    }

    @Test
    void settlesRealSponsorChainExactlyOnceAndCreditsBothWallets() {
        when(mapper.findPendingReferrals(any(LocalDateTime.class), eq(true), eq(10)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));
        when(mapper.insertSettlement(anyString(), eq(22L), eq(11L), any(), any(), any(),
                eq("risk_bucket"), anyString(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class), eq(true))).thenReturn(1);

        Map<String, Object> result = service.runSettlements("idem-ref-1",
                new ReferralSettlementRunRequest(10, "manual reconciliation", "superadmin"));

        assertThat(result).containsEntry("settled", 1).containsEntry("skipped", 0);
        verify(mapper).lockRewardMutation();
        verify(mapper).creditWallet(eq(22L), decimal("5"), decimal("20"));
        verify(mapper).creditWallet(eq(11L), decimal("0"), decimal("10"));
        verify(ledger).postLedgerEntry(anyString(), eq(22L), eq("REFERRAL_REWARD"), eq("USDT"), eq("IN"), decimal("5"), eq("SUCCESS"), anyString());
        verify(ledger).postLedgerEntry(anyString(), eq(11L), eq("REFERRAL_REWARD"), eq("NEX"), eq("IN"), decimal("10"), eq("SUCCESS"), anyString());
        verify(audit, times(2)).recordRequired(any());
    }

    @Test
    void finalAtomicEligibilityFailureNeverCreditsWalletOrLedger() {
        when(mapper.findPendingReferrals(any(LocalDateTime.class), eq(true), eq(10)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));

        Map<String, Object> result = service.runSettlements("idem-ref-race",
                new ReferralSettlementRunRequest(10, "final risk race verification", "superadmin"));

        assertThat(result).containsEntry("settled", 0).containsEntry("skipped", 1);
        verify(mapper, never()).creditWallet(any(), any(), any());
        verify(ledger, never()).postLedgerEntry(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
    }

    @Test
    void settlementTransactionUsesSerializableIsolation() throws Exception {
        Transactional transactional = OpsReferralRewardService.class
                .getMethod("runSettlements", String.class, ReferralSettlementRunRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.SERIALIZABLE);
    }

    @Test
    void overviewExposesTheSamePhaseAdjustedAmountsUsedBySettlement() {
        when(config.activeValue("growth.phase.month.7.newUserBonusMultiplier"))
                .thenReturn(Optional.of("1.5"));
        when(config.activeValue("growth.phase.month.7.inviteRewardMultiplier"))
                .thenReturn(Optional.of("2"));

        Map<String, Object> overview = service.overview();

        assertThat(overview).containsEntry("rhythmMonth", 7);
        assertThat(overview).containsEntry("newcomerMultiplier", new BigDecimal("1.5"));
        assertThat(overview).containsEntry("inviterMultiplier", new BigDecimal("2"));
        Map<String, Object> effective = (Map<String, Object>) overview.get("effectiveRewards");
        assertThat((BigDecimal) effective.get("newcomer.usdt")).isEqualByComparingTo("7.5");
        assertThat((BigDecimal) effective.get("newcomer.nex")).isEqualByComparingTo("30");
        assertThat((BigDecimal) effective.get("inviter.nex")).isEqualByComparingTo("20");
    }

    @Test
    void missingDatabaseMutexFailsClosedBeforeSettlement() {
        when(mapper.lockRewardMutation()).thenReturn(null);

        assertThatThrownBy(() -> service.runSettlements("idem-ref-no-mutex",
                new ReferralSettlementRunRequest(10, "mutex health verification", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_REWARD_MUTEX_UNAVAILABLE");

        verify(mapper, never()).findPendingReferrals(any(LocalDateTime.class), anyBoolean(), any(Integer.class));
    }

    @Test
    void postSettlementCoverageDropRollsBackTheBatch() {
        when(mapper.findPendingReferrals(any(LocalDateTime.class), eq(true), eq(10)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));
        when(mapper.insertSettlement(anyString(), eq(22L), eq(11L), any(), any(), any(),
                eq("risk_bucket"), anyString(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class), eq(true))).thenReturn(1);
        when(coverage.snapshot()).thenReturn(
                new TreasuryCoverageSnapshot(new BigDecimal("100"), new BigDecimal("85")),
                new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("85")));

        assertThatThrownBy(() -> service.runSettlements("idem-ref-post-b1",
                new ReferralSettlementRunRequest(10, "post award coverage verification", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_BELOW_REDLINE");
    }

    @Test
    void unavailableOrUnreliableCoverageFailsClosed() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, false));

        assertThatThrownBy(() -> service.runSettlements("idem-ref-b1-unavailable",
                new ReferralSettlementRunRequest(10, "coverage source verification", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_DATA_UNAVAILABLE");

        verify(mapper, never()).findPendingReferrals(any(LocalDateTime.class), anyBoolean(), any(Integer.class));
    }

    @Test
    void rejectsRewardIncreaseWhenB1CoverageIsBelowRedline() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("85")));

        assertThatThrownBy(() -> service.updateParam("newcomer.usdt", "idem-ref-b1",
                new GrowthConfigUpdateRequest("newcomer.usdt", "6", "raise welcome gift budget", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_BELOW_REDLINE");

        verify(config, never()).upsertAdminValue(eq("K.rewards.welcomeGift.usdtAmount"), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void rejectsSettlementRunWhenB1CoverageIsBelowRedline() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("85")));

        assertThatThrownBy(() -> service.runSettlements("idem-ref-b1-run",
                new ReferralSettlementRunRequest(10, "manual reconciliation", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_BELOW_REDLINE");

        verify(mapper, never()).findPendingReferrals(any(LocalDateTime.class), anyBoolean(), any(Integer.class));
    }

    @Test
    void missingMoneyConfigurationFailsSafeInsteadOfUsingPrototypeAmounts() {
        when(config.activeValue("K.rewards.welcomeGift.usdtAmount")).thenReturn(Optional.empty());
        when(config.activeValue("K.rewards.welcomeGift.nexAmount")).thenReturn(Optional.empty());
        when(config.activeValue("K.rewards.inviterReward.nexAmount")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runSettlements("idem-ref-no-config",
                new ReferralSettlementRunRequest(10, "manual reconciliation", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("REFERRAL_REWARD_NOT_CONFIGURED");

        verify(mapper, never()).creditWallet(any(), any(), any());
        verify(ledger, never()).postLedgerEntry(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
    }

    private static BigDecimal decimal(String expected) {
        BigDecimal target = new BigDecimal(expected);
        return argThat(actual -> actual != null && actual.compareTo(target) == 0);
    }
}
