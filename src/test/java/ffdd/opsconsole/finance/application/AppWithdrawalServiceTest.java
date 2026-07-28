package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.Attribution;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.KycWalletRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WalletRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalRiskFacts;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalWrite;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import ffdd.opsconsole.risk.facade.WithdrawalRiskRuleFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AppWithdrawalServiceTest {
    private final AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final GrowthRhythmFacade rhythmFacade = mock(GrowthRhythmFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final WithdrawalRiskRuleFacade k3 = mock(WithdrawalRiskRuleFacade.class);
    private final RiskKycReviewFacade k5 = mock(RiskKycReviewFacade.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final AppWithdrawalService service = new AppWithdrawalService(
            mapper, config, rhythmFacade, idempotency, audit, outbox, k3, k5, ledger);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.findActiveUser(7L)).thenReturn(7L);
        when(mapper.lockKycWallet(7L)).thenReturn(new KycWalletRow(
                "APPROVED", "TR7NHqExampleAddress", "TRC20"));
        when(mapper.countLast24Hours(7L)).thenReturn(0);
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        45, "k4-v13", LocalDateTime.now(), 41, 73, 91));
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(
                7L, new BigDecimal("500.000000"), new BigDecimal("50.000000"), BigDecimal.ZERO, 3L));
        when(config.activeValue("withdrawal.trc20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.daily_count_limit")).thenReturn(Optional.of("2"));
        when(config.activeValue("withdrawal.max_balance_pct")).thenReturn(Optional.of("0.8"));
        when(config.activeValue("withdrawal.nex_fee_offset_rate")).thenReturn(Optional.of("0.4"));
        when(config.activeValue("withdrawal.fee_rate")).thenReturn(Optional.of("0.02"));
        when(config.activeValue("withdrawal.fee_min_usdt")).thenReturn(Optional.of("0.50"));
        when(config.activeValue("withdrawal.fee_max_usdt")).thenReturn(Optional.of("20.00"));
        GrowthRhythmSnapshot rhythm = mock(GrowthRhythmSnapshot.class);
        when(rhythm.currentMonth()).thenReturn(3);
        when(rhythm.currentPhase()).thenReturn("P2");
        when(rhythm.withdrawCooldownDays()).thenReturn(30);
        when(rhythm.withdrawPenaltyFeeRate()).thenReturn(new BigDecimal("20"));
        when(rhythmFacade.snapshot()).thenReturn(rhythm);
        when(mapper.reserveFunds(eq(7L), any(), any(), eq(3L))).thenReturn(1);
        when(mapper.insertWithdrawal(any())).thenReturn(1);
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 1, "2026-W30"));
        when(k3.evaluate(any())).thenReturn(new WithdrawalRiskDecision("pass", null, null, java.util.List.of()));
        when(k5.triggerLargeWithdrawalReview(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(KycReviewTriggerResult.notRequired());
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void snapshotsH1FeeAndCooldownBurnsOptionalNexAndPostsAuditableD4Components() {
        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("penaltyFeeRate", new BigDecimal("20"))
                .containsEntry("networkFee", new BigDecimal("2.000000"))
                .containsEntry("grossFee", new BigDecimal("22.000000"))
                .containsEntry("nexBurned", new BigDecimal("50.000000"))
                .containsEntry("actualFee", new BigDecimal("2.000000"))
                .containsEntry("penaltyFeeWaived", new BigDecimal("20.000000"))
                .containsEntry("networkFeeWaived", new BigDecimal("0.000000"))
                .containsEntry("actualPenaltyFee", new BigDecimal("0.000000"))
                .containsEntry("actualNetworkFee", new BigDecimal("2.000000"))
                .containsEntry("netReceive", new BigDecimal("98.000000"))
                .containsEntry("status", "REVIEW_PENDING");
        verify(mapper).reserveFunds(7L, new BigDecimal("100.000000"), new BigDecimal("50.000000"), 3L);
        verify(ledger).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_NET_PRINCIPAL"), eq("USDT"), eq("OUT"),
                eq(new BigDecimal("98.000000")), eq("POSTED"), eq("D2 withdrawal net principal"));
        verify(ledger).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_NETWORK_FEE"), eq("USDT"), eq("OUT"),
                eq(new BigDecimal("2.000000")), eq("POSTED"),
                eq("D5 actual network fee after NEX offset"));
        verify(ledger).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_FEE_OFFSET"), eq("NEX"), eq("OUT"),
                eq(new BigDecimal("50.000000")), eq("POSTED"),
                eq("D5 NEX fee offset; penalty first, then network fee"));
        verify(ledger, never()).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_PENALTY_FEE"), eq("USDT"), eq("OUT"),
                any(), anyString(), anyString());
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().freezePeriod()).isEqualTo("H1:M3:P2");
        assertThat(write.getValue().penaltyFeeRate()).isEqualByComparingTo("20");
        assertThat(write.getValue().networkFeeRate()).isEqualByComparingTo("0.02");
        assertThat(write.getValue().networkFeeMin()).isEqualByComparingTo("0.50");
        assertThat(write.getValue().networkFeeMax()).isEqualByComparingTo("20.00");
        assertThat(write.getValue().networkFee()).isEqualByComparingTo("2.000000");
        assertThat(write.getValue().holdUntil()).isAfter(java.time.LocalDateTime.now().plusDays(29));
        verify(outbox).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("withdraw.submitted"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void exactIdempotencyReplayDoesNotDuplicateAnyD4LedgerComponent() {
        AtomicReference<ApiResult> stored = new AtomicReference<>();
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    ApiResult existing = stored.get();
                    if (existing != null) return existing;
                    ApiResult created = (ApiResult) ((Supplier) invocation.getArgument(4)).get();
                    stored.set(created);
                    return created;
                });

        ApiResult<java.util.Map<String, Object>> first = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-replay");
        ApiResult<java.util.Map<String, Object>> replay = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-replay");

        assertThat(replay.getData().get("withdrawalNo")).isEqualTo(first.getData().get("withdrawalNo"));
        verify(ledger, org.mockito.Mockito.times(3)).postLedgerEntry(
                anyString(), eq(7L), anyString(), anyString(), eq("OUT"),
                any(), eq("POSTED"), anyString());
        verify(mapper, org.mockito.Mockito.times(1)).reserveFunds(
                eq(7L), any(), any(), eq(3L));
        verify(mapper, org.mockito.Mockito.times(1)).insertWithdrawal(any());
    }

    @Test
    void exposesOnlyBackendSupportedNetworksAndExactD5H1PreviewPolicy() {
        when(config.activeValue("withdrawal.erc20.enabled")).thenReturn(Optional.of("false"));

        ApiResult<java.util.Map<String, Object>> result = service.policy(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("networkFeeRate", new BigDecimal("0.02"))
                .containsEntry("networkFeeMin", new BigDecimal("0.50"))
                .containsEntry("networkFeeMax", new BigDecimal("20.00"))
                .containsEntry("penaltyFeeRate", new BigDecimal("20"))
                .containsEntry("cooldownDays", 30)
                .containsEntry("withdrawalEnabled", true)
                .containsEntry("gateSource", "J1")
                .containsEntry("enabledNetworks", java.util.List.of("USDT-TRC20"))
                .containsEntry("source", "D5+H1");
    }

    @Test
    void j1WithdrawGateBlocksTheRealUserEntryBeforeAnyFinancialOrRiskSideEffect() {
        when(mapper.emergencyValue("killswitch.withdraw")).thenReturn("disabled");

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-j1-kill");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_KILL_SWITCH_DISABLED");
        verify(mapper, never()).lockKycWallet(any());
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
        verify(k3, never()).evaluate(any());
        verify(k5, never()).triggerLargeWithdrawalReview(
                anyString(), any(), anyString(), anyString(), anyString(), anyString());
        verify(ledger, never()).postLedgerEntry(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publishUserEvent(
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void policyProjectsTheJ1WithdrawGateForAppFailClosedRendering() {
        when(config.activeValue("withdrawal.erc20.enabled")).thenReturn(Optional.of("false"));
        when(mapper.emergencyValue("killswitch.withdraw")).thenReturn("disabled");

        ApiResult<java.util.Map<String, Object>> result = service.policy(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("withdrawalEnabled", false)
                .containsEntry("gateSource", "J1");
    }

    @Test
    void rejectsClientAddressThatDoesNotMatchServerPairedWalletWithoutMutation() {
        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqDifferent", "wd-2");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_PAIRED_WALLET_MISMATCH");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
    }

    @Test
    void enforcesServerDailyLimitBeforeFundsMove() {
        when(mapper.countLast24Hours(7L)).thenReturn(2);

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-3");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_DAILY_LIMIT_EXCEEDED");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
    }

    @Test
    void failsClosedWithoutCurrentK4ScoreBeforeAnyFundsSideEffect() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        null, null, null, 41, 73, 91));

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-missing"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("K4_RISK_SCORE_UNAVAILABLE");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
        verify(ledger, never()).postLedgerEntry(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publishUserEvent(
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void failsClosedOnStaleK4ScoreBeforeAnyFundsSideEffect() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        45, "k4-v13", LocalDateTime.now().minusDays(2), 41, 73, 91));

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-stale"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("K4_RISK_SCORE_UNAVAILABLE");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
        verify(ledger, never()).postLedgerEntry(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void lowK4WithNoStricterGateWaitsForH1CooldownBeforeCanonicalApproval() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        40, "k4-v13", LocalDateTime.now(), 41, 73, 91));

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-low");

        assertThat(result.getData()).containsEntry("status", "EXTENDED_HOLD")
                .containsEntry("riskRoute", "fast-pass")
                .containsEntry("k4Priority", "LOW");
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().status()).isEqualTo("EXTENDED_HOLD");
        assertThat(write.getValue().previousStatus()).isEqualTo("REVIEW_PASSED");
        assertThat(write.getValue().routingPriority()).isEqualTo("LOW");
        assertThat(write.getValue().holdUntil()).isAfter(LocalDateTime.now().plusDays(29));
        verify(outbox, never()).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("withdraw.approved"), eq(7L),
                anyString(), any(), any(), any());
    }

    @Test
    void escalatedK4UsesManualQueueWithoutFreezingAndNotifiesRiskLead() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        91, "k4-v13", LocalDateTime.now(), 41, 73, 91));

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-escalated");

        assertThat(result.getData()).containsEntry("status", "REVIEW_PENDING")
                .containsEntry("riskRoute", "escalated-manual")
                .containsEntry("k4Priority", "ESCALATED");
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().failureReason()).startsWith("K4_ESCALATED:91");
        assertThat(write.getValue().routingPriority()).isEqualTo("ESCALATED");
        verify(outbox).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("risk.withdraw_escalated"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
    }
}
