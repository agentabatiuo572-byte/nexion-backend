package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelEvent;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelTier;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppGrowthWheelServiceTest {
    private final AppGrowthWheelMapper mapper = mock(AppGrowthWheelMapper.class);
    private final VoucherGrantFacade voucher = mock(VoucherGrantFacade.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AppGrowthWheelService service =
            new AppGrowthWheelService(mapper, voucher, coverage, idempotency, audit, outbox);

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
