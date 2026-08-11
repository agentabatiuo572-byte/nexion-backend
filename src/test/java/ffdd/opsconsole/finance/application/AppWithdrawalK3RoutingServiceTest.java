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
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.PayoutAddressRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WalletRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalRiskFacts;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalWrite;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import ffdd.opsconsole.risk.facade.WithdrawalRiskRuleFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
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
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final AppWithdrawalService service = new AppWithdrawalService(
            mapper, config, rhythmFacade, idempotency, audit, outbox, k3, ledger, null);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.lockPayoutAddress(7L, "USDT-TRC20")).thenReturn(new PayoutAddressRow(
                "USDT-TRC20", "TR7NHqExampleAddress", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(6)));
        when(mapper.countLast24Hours(7L)).thenReturn(3);
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(
                7L, new BigDecimal("5000.000000"), new BigDecimal("50.000000"), BigDecimal.ZERO, 3L));
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 3, new BigDecimal("4900.000000"), 3, "low",
                        78, "k4-v13", LocalDateTime.now(), 41, 73, 91));
        when(config.activeValue("withdrawal.trc20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.bep20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.erc20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.d5.version")).thenReturn(Optional.of("1"));
        when(config.activeValue("withdrawal.network_confirm_fee_usd.trc20")).thenReturn(Optional.of("1"));
        when(config.activeValue("withdrawal.network_confirm_fee_usd.bep20")).thenReturn(Optional.of("1"));
        when(config.activeValue("withdrawal.network_confirm_fee_usd.erc20")).thenReturn(Optional.of("5"));
        when(config.activeValue("withdrawal.daily_count_limit")).thenReturn(Optional.of("10"));
        when(config.activeValue("withdrawal.max_balance_pct")).thenReturn(Optional.of("0.8"));
        when(config.activeValue("withdrawal.nex_fee_offset_rate")).thenReturn(Optional.of("0.4"));
        when(config.activeValue("withdrawal.small_amount_threshold_usd")).thenReturn(Optional.of("50"));
        when(config.activeValue("withdrawal.strong_review_threshold_usdt")).thenReturn(Optional.of("1000"));
        when(config.activeValue("withdrawal.payout_sla_hours")).thenReturn(Optional.of("24"));
        when(config.activeValue("withdrawal.fee_rate")).thenReturn(Optional.of("0.001"));
        when(config.activeValue("withdrawal.fee_min_usdt")).thenReturn(Optional.of("0.1"));
        when(config.activeValue("withdrawal.fee_max_usdt")).thenReturn(Optional.of("5"));
        GrowthRhythmSnapshot rhythm = mock(GrowthRhythmSnapshot.class);
        when(rhythm.currentMonth()).thenReturn(3);
        when(rhythm.currentPhase()).thenReturn("P2");
        when(rhythm.withdrawCooldownDays()).thenReturn(30);
        when(rhythm.withdrawPenaltyFeeRate()).thenReturn(new BigDecimal("20"));
        when(rhythmFacade.snapshot()).thenReturn(rhythm);
        when(mapper.reserveFunds(eq(7L), any(), any(), eq(3L))).thenReturn(1);
        when(mapper.insertWithdrawal(any())).thenReturn(1);
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 1, "2026-W30"));
        when(k3.evaluate(any())).thenReturn(new WithdrawalRiskDecision("pass", null, null, List.of()));
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
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_PAYOUT_ADDRESS_MISMATCH");
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
