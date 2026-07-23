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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
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
    private final AppWithdrawalService service = new AppWithdrawalService(
            mapper, config, rhythmFacade, idempotency, audit, outbox, k3, k5);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
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
        GrowthRhythmSnapshot rhythm = mock(GrowthRhythmSnapshot.class);
        when(rhythm.currentMonth()).thenReturn(3);
        when(rhythm.currentPhase()).thenReturn("P2");
        when(rhythm.withdrawCooldownDays()).thenReturn(30);
        when(rhythm.withdrawPenaltyFeeRate()).thenReturn(new BigDecimal("20"));
        when(rhythmFacade.snapshot()).thenReturn(rhythm);
        when(mapper.reserveFunds(eq(7L), any(), any(), eq(3L))).thenReturn(1);
        when(mapper.insertWithdrawal(any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 1, "2026-W30"));
        when(k3.evaluate(any())).thenReturn(new WithdrawalRiskDecision("pass", null, null, java.util.List.of()));
        when(k5.triggerLargeWithdrawalReview(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(KycReviewTriggerResult.notRequired());
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void snapshotsH1FeeAndCooldownBurnsOptionalNexAndWritesBothLedgers() {
        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("penaltyFeeRate", new BigDecimal("20"))
                .containsEntry("grossFee", new BigDecimal("20.000000"))
                .containsEntry("nexBurned", new BigDecimal("50.000000"))
                .containsEntry("actualFee", new BigDecimal("0.000000"))
                .containsEntry("netReceive", new BigDecimal("100.000000"))
                .containsEntry("status", "REVIEW_PENDING");
        verify(mapper).reserveFunds(7L, new BigDecimal("100.000000"), new BigDecimal("50.000000"), 3L);
        verify(mapper, org.mockito.Mockito.times(2)).insertLedger(any());
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().freezePeriod()).isEqualTo("H1:M3:P2");
        assertThat(write.getValue().penaltyFeeRate()).isEqualByComparingTo("20");
        assertThat(write.getValue().holdUntil()).isAfter(java.time.LocalDateTime.now().plusDays(29));
        verify(outbox).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("withdraw.submitted"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
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
        verify(mapper, never()).insertLedger(any());
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
        verify(mapper, never()).insertLedger(any());
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
