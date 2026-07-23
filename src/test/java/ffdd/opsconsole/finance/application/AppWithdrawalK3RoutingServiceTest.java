package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import ffdd.opsconsole.risk.facade.WithdrawalRiskRuleFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AppWithdrawalK3RoutingServiceTest {
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
        when(mapper.countLast24Hours(7L)).thenReturn(3);
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(
                7L, new BigDecimal("5000.000000"), new BigDecimal("50.000000"), BigDecimal.ZERO, 3L));
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 3, new BigDecimal("4900.000000"), 3, "low",
                        78, "k4-v13", LocalDateTime.now(), 41, 73, 91));
        when(config.activeValue("withdrawal.trc20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.daily_count_limit")).thenReturn(Optional.of("10"));
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
        when(k3.evaluate(any())).thenReturn(new WithdrawalRiskDecision("pass", null, null, List.of()));
        when(k5.triggerLargeWithdrawalReview(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(KycReviewTriggerResult.notRequired());
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void usesCanonical24hAgeAndAddressFactsAndRoutesFreezeAtQueueEntry() {
        RiskRuleView rule = rule("WR-ADDRESS", "地址信誉", "内部黑名单", "freeze", 90);
        when(k3.evaluate(any())).thenReturn(
                new WithdrawalRiskDecision("freeze", rule.ruleId(), rule.dimension(), List.of(rule)));

        ApiResult<Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k3-freeze");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "FROZEN")
                .containsEntry("riskRoute", "freeze")
                .containsEntry("riskRuleId", "WR-ADDRESS");
        ArgumentCaptor<WithdrawalRiskContext> context = ArgumentCaptor.forClass(WithdrawalRiskContext.class);
        verify(k3).evaluate(context.capture());
        assertThat(context.getValue().withdrawalCount24h()).isEqualTo(4);
        assertThat(context.getValue().withdrawalSum24h()).isEqualByComparingTo("5000.000000");
        assertThat(context.getValue().accountAgeDays()).isEqualTo(3);
        assertThat(context.getValue().addressReputation()).isEqualTo("low");
        verify(k3).recordDecision(eq(context.getValue()), any());
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().status()).isEqualTo("FROZEN");
        assertThat(write.getValue().failureReason()).startsWith("K3_ROUTE:freeze:WR-ADDRESS");
        verify(outbox).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("risk.withdraw_held"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
    }

    @Test
    void layersK5OnTheSameK3ConclusionWithoutReplacingTheK3Event() {
        RiskRuleView rule = rule("WR-AMOUNT", "金额", "单笔 >= $1,000", "manual", 90);
        when(k3.evaluate(any())).thenReturn(
                new WithdrawalRiskDecision("manual", rule.ruleId(), rule.dimension(), List.of(rule)));
        when(k5.triggerLargeWithdrawalReview(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new KycReviewTriggerResult(true, true, "KR-D2-ONE", "K5_LARGE_WITHDRAWAL_REVIEW_REQUIRED"));

        ApiResult<Map<String, Object>> result = service.submit(
                7L, new BigDecimal("1000"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k3-k5");

        assertThat(result.getData()).containsEntry("status", "FROZEN")
                .containsEntry("riskRoute", "freeze")
                .containsEntry("k3RiskRoute", "manual")
                .containsEntry("k5TicketId", "KR-D2-ONE");
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().failureReason())
                .contains("K5_REVIEW:KR-D2-ONE", "K3_ROUTE:manual:WR-AMOUNT", "K4_HIGH_PRIORITY:78");
        verify(k3).recordDecision(any(), any());
        verify(outbox).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("risk.withdraw_held"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void idempotentReplayDoesNotDuplicateHitDecisionOrHeldEvent() {
        RiskRuleView rule = rule("WR-VELOCITY", "速度", "24h > 3 笔 或 > $5,000", "delay", 90);
        when(k3.evaluate(any())).thenReturn(
                new WithdrawalRiskDecision("delay", rule.ruleId(), rule.dimension(), List.of(rule)));
        AtomicReference<ApiResult> cached = new AtomicReference<>();
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    ApiResult current = cached.get();
                    if (current != null) return current;
                    ApiResult created = (ApiResult) ((Supplier) invocation.getArgument(4)).get();
                    cached.set(created);
                    return created;
                });

        ApiResult<Map<String, Object>> first = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k3-replay");
        ApiResult<Map<String, Object>> replay = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k3-replay");

        assertThat(replay.getData()).isEqualTo(first.getData());
        assertThat(first.getData()).containsEntry("status", "EXTENDED_HOLD")
                .containsEntry("riskRoute", "high-manual")
                .containsEntry("k3RiskRoute", "delay");
        verify(mapper, times(1)).insertWithdrawal(any());
        verify(k3, times(1)).recordDecision(any(), any());
        verify(outbox, times(1)).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("risk.withdraw_held"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
    }

    @Test
    void failsClosedBeforeFundsMoveWhenTheK3RuleSetCannotBeEvaluated() {
        when(k3.evaluate(any())).thenThrow(new IllegalStateException("K3_ACTIVE_RULE_INVALID"));

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k3-invalid"))
                .isInstanceOf(BizException.class)
                .hasMessage("K3_WITHDRAWAL_DECISION_UNAVAILABLE");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
    }

    @Test
    void rejectsCaseChangedTronAddressBeforeRiskScoringOrFundsMove() {
        ApiResult<Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20",
                "tr7nhqexampleaddress", "wd-k3-tron-case-change");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_PAIRED_WALLET_MISMATCH");
        verify(k3, never()).evaluate(any());
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
    }

    private RiskRuleView rule(String id, String dimension, String condition, String action, int priority) {
        return new RiskRuleView(
                id, dimension, condition, action, "active", false, priority, 0L,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
    }
}
