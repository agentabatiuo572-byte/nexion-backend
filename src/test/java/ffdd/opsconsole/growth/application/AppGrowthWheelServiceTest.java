package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelEvent;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelTier;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.SpinTicket;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppGrowthWheelServiceTest {
    private final AppGrowthWheelMapper mapper = mock(AppGrowthWheelMapper.class);
    private final VoucherGrantFacade voucher = mock(VoucherGrantFacade.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AppGrowthWheelService service =
            new AppGrowthWheelService(mapper, voucher, coverage, idempotency, audit, outbox, null, null);

    @BeforeEach
    void setUp() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockWheelPayoutMutex()).thenReturn("H4_WHEEL_PAYOUT");
        when(mapper.lockOpenWheelEvent("evt-spring-spin"))
                .thenReturn(new WheelEvent(8L, "evt-spring-spin"));
        when(mapper.attribution(42L)).thenReturn(new Attribution("P2", 3, "2026-W30"));
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class),
                org.mockito.ArgumentMatchers.<Supplier<ApiResult>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void b1BelowRedlineDowngradesCertainUsdtResultToComfortNexTier() {
        when(mapper.countDailySpin(eq(8L), eq(42L), any(LocalDate.class))).thenReturn(0);
        WheelTier comfort = new WheelTier(
                1L, "comfort-nex-5", "+5 NEX", BigDecimal.ZERO, false,
                "nex", new BigDecimal("5"), null, 0);
        WheelTier usdt = new WheelTier(
                2L, "usdt-500", "$500 USDT", new BigDecimal("100.0000"), true,
                "usdt", new BigDecimal("500"), null, 5);
        when(mapper.lockActiveTiers()).thenReturn(List.of(comfort, usdt));
        when(mapper.lockGuardValue("kill")).thenReturn("on");
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("0.99"), BigDecimal.ONE, true));
        when(mapper.insertSpin(anyString(), eq(new WheelEvent(8L, "evt-spring-spin")), eq(42L),
                any(LocalDate.class), eq("DAILY"), anyString(), eq(comfort), eq(true),
                eq("B1_COVERAGE_REDLINE"))).thenReturn(1);
        when(mapper.lockWalletNex(42L)).thenReturn(BigDecimal.TEN);
        when(mapper.creditWalletNex(42L, new BigDecimal("5"))).thenReturn(1);
        when(mapper.insertWalletLedger(eq(42L), anyString(), eq("NEX"),
                eq(new BigDecimal("5")), eq(new BigDecimal("15")))).thenReturn(1);

        var result = service.spin(42L, "evt-spring-spin", "spin-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("downgraded", true)
                .containsEntry("rewardType", "NEX")
                .containsEntry("downgradeReason", "B1_COVERAGE_REDLINE");
        verify(mapper, never()).creditWalletUsdt(any(), any());
        verify(outbox).publishUserEvent(
                eq("WHEEL_SPIN"), anyString(), eq("event.spin_awarded"), eq(42L),
                eq("P2"), eq(3), eq("2026-W30"), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedComfortTierIsSkippedForBothStockAndCashGuardDowngrades(boolean cashPrize) {
        WheelTier exhausted = new WheelTier(1L, "full-nex", "+5 NEX",
                cashPrize ? BigDecimal.ZERO : new BigDecimal("100"), false,
                "nex", new BigDecimal("5"), null, 1);
        WheelTier available = new WheelTier(2L, "available-nex", "+3 NEX", BigDecimal.ZERO,
                false, "nex", new BigDecimal("3"), null, 2);
        WheelTier usdt = new WheelTier(3L, "usdt-1", "$1 USDT", new BigDecimal("100"),
                true, "usdt", BigDecimal.ONE, null, 0);
        when(mapper.lockActiveTiers()).thenReturn(cashPrize
                ? List.of(exhausted, available, usdt) : List.of(exhausted, available));
        when(mapper.currentTierDailyAwards(any(LocalDate.class), eq(1L))).thenReturn(1);
        when(mapper.currentTierDailyAwards(any(LocalDate.class), eq(2L))).thenReturn(1);
        String reason = cashPrize ? "REAL_PRIZE_DISABLED" : "TIER_DAILY_STOCK_EXHAUSTED";
        when(mapper.insertSpin(anyString(), any(), eq(42L), any(LocalDate.class), eq("DAILY"),
                anyString(), eq(available), eq(true), eq(reason))).thenReturn(1);
        when(mapper.lockWalletNex(42L)).thenReturn(BigDecimal.TEN);
        when(mapper.creditWalletNex(42L, new BigDecimal("3"))).thenReturn(1);
        when(mapper.insertWalletLedger(eq(42L), anyString(), eq("NEX"),
                eq(new BigDecimal("3")), eq(new BigDecimal("13")))).thenReturn(1);

        var result = service.spin(42L, "evt-spring-spin", "stock-fallback");

        assertThat(result.getData()).containsEntry("tierId", "available-nex")
                .containsEntry("rewardAmount", new BigDecimal("3"))
                .containsEntry("downgraded", true).containsEntry("downgradeReason", reason);
        verify(mapper, never()).creditWalletNex(42L, new BigDecimal("5"));
        verify(mapper, never()).creditWalletUsdt(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void exhaustedComfortPoolPreservesBothDailyAndBonusEligibility(int usedDaily) {
        when(mapper.countDailySpin(eq(8L), eq(42L), any(LocalDate.class))).thenReturn(usedDaily);
        when(mapper.lockAvailableTicket(42L)).thenReturn(new SpinTicket("bonus-ticket"));
        WheelTier comfort = new WheelTier(1L, "full-nex", "+5 NEX", BigDecimal.ZERO,
                false, "nex", new BigDecimal("5"), null, 1);
        WheelTier otherNonCash = new WheelTier(2L, "points-1", "+1 point", BigDecimal.ZERO,
                false, "points", BigDecimal.ONE, null, 0);
        WheelTier usdt = new WheelTier(3L, "usdt-1", "$1 USDT", new BigDecimal("100"),
                true, "usdt", BigDecimal.ONE, null, 0);
        when(mapper.lockActiveTiers()).thenReturn(List.of(comfort, otherNonCash, usdt));
        when(mapper.currentTierDailyAwards(any(LocalDate.class), eq(1L))).thenReturn(1);

        assertThatThrownBy(() -> service.spin(42L, "evt-spring-spin", "full-comfort-pool"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("WHEEL_COMFORT_TIER_MISSING");
                });

        verify(mapper, never()).insertSpin(anyString(), any(), any(), any(), anyString(),
                anyString(), any(), anyBoolean(), anyString());
        verify(mapper, never()).consumeTicket(anyString(), any(), anyString(), any());
        verify(mapper, never()).creditWalletNex(any(), any());
        verify(mapper, never()).creditWalletUsdt(any(), any());
        verify(mapper, never()).insertPointsLedger(any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(voucher, audit, outbox);
    }

    @Test
    void endedWheelExposesOnlyThisUsersHistoryAndNeverTodaysTicketsOrPool() {
        when(mapper.findActiveUser(42L)).thenReturn(42L);
        when(mapper.findEndedWheelEvent("evt-anniversary-spin"))
                .thenReturn(new WheelEvent(9L, "evt-anniversary-spin"));
        when(mapper.listWheelHistory(42L, "evt-anniversary-spin", 20)).thenReturn(List.of(Map.of(
                "spinId", "prior-spin", "tierName", "prior-tier", "rewardType", "NEX",
                "rewardAmount", 5, "spinDate", "2025-10-01", "sourceType", "DAILY",
                "awardedAt", "2025-10-01T01:00:00", "downgraded", false)));

        var result = service.state(42L, "evt-anniversary-spin");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("ended", true).containsEntry("availableSpins", 0)
                .containsEntry("freeAvailable", false).containsEntry("segments", List.of());
        assertThat((List<?>) result.getData().get("history")).hasSize(1);
        verify(mapper, never()).listActiveTiers();
        verify(mapper, never()).countAvailableTickets(any());
        verify(mapper, never()).lockActiveTiers();
        assertThat(service.spin(42L, "evt-anniversary-spin", "closed-spin").getCode()).isEqualTo(409);
    }

    @Test
    void secondDailySpinWithoutBonusTicketIsRejectedBeforeRngOrReward() {
        when(mapper.countDailySpin(eq(8L), eq(42L), any(LocalDate.class))).thenReturn(1);
        when(mapper.lockAvailableTicket(42L)).thenReturn(null);

        var result = service.spin(42L, "evt-spring-spin", "spin-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WHEEL_DAILY_LIMIT_REACHED");
        verify(mapper, never()).lockActiveTiers();
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
