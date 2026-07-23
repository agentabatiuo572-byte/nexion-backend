package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.PolicyRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.WalletRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppTrialLifecycleServiceTest {
    private final AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AppTrialLifecycleService service = new AppTrialLifecycleService(
            mapper, idempotency, coverage, audit, outbox);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialDays", "3"),
                new PolicyRow("shadowDailyUSD", "40"),
                new PolicyRow("shadowDailyNEX", "5"),
                new PolicyRow("trialOffsetCapUSD", "50"),
                new PolicyRow("trialPriceUSD", "1299")));
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 2, "2026-W30"));
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("85")));
    }

    @Test
    void stateRejectsDeletedOrInactiveAuthenticatedIdentity() {
        when(mapper.activeUser(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("USER_NOT_FOUND");
        verify(mapper, never()).trial(7L);
    }

    @Test
    void trialCycleSignalBlocksBeforeClaimMutation() {
        when(mapper.trialCycleSignalCount(7L)).thenReturn(1L);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-cycle");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_CYCLE_RISK_BLOCKED");
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void redeemedTrialCanNeverBeRestarted() {
        TrialRow redeemed = new TrialRow(1L, 7L, "TRIAL-USED", "REDEEMED", 9L, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), LocalDateTime.now().minusDays(3), LocalDateTime.now(),
                new BigDecimal("120"), new BigDecimal("15"), new BigDecimal("70"),
                new BigDecimal("50"), new BigDecimal("1199"), null, 2L);
        when(mapper.lockTrial(7L)).thenReturn(redeemed);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-reuse");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_ALREADY_REDEEMED");
        verify(mapper, never()).restartTrial(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void insufficientWalletReturnsCanonicalFailureWithoutSettlementMutation() {
        TrialRow active = activeTrial();
        when(mapper.lockTrial(7L)).thenReturn(active);
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(new BigDecimal("10"), BigDecimal.ZERO));

        ApiResult<Map<String, Object>> result = service.redeemEarly(7L, "h2-low-balance");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("ok", false)
                .containsEntry("reason", "INSUFFICIENT_FUNDS")
                .containsEntry("paymentRail", "NEXION_USDT_WALLET");
        verify(mapper, never()).settleWallet(any(), any(), any(), any());
        verify(outbox).publishUserEvent(eq("TRIAL"), eq("TRIAL-1"), eq("trial.charge_attempted"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
    }

    private TrialRow activeTrial() {
        LocalDateTime now = LocalDateTime.now();
        return new TrialRow(1L, 7L, "TRIAL-1", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), now.minusDays(1), now.plusDays(2),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 0L);
    }
}
