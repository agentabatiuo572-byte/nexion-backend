package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;

class G2ExchangeQueueBatchServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"USDT","NEX"})
    void geoCancelReturnsReservedSourceBeforeBindingForBothAssets(String asset){
        when(mapper.lockQueuedBatch(1)).thenReturn(List.of(new AppExchangeMapper.QueuedRow(8L,"EX-GEO-HELD",asset,BigDecimal.TEN)));
        when(mapper.lockActiveUserNo(8L)).thenReturn("U00000008");
        when(mapper.lockWalletGate(8L)).thenReturn(new AppExchangeMapper.WalletGateRow(new BigDecimal("100"),new BigDecimal("100"),"KP"));
        when(mapper.geoBlocked("KP")).thenReturn(1);when(mapper.cancelQueuedBySystem("EX-GEO-HELD")).thenReturn(1);when(mapper.sourceReservationExists("EX-GEO-HELD")).thenReturn(1);when(mapper.applyWalletDelta(eq(8L),any(),any())).thenReturn(1);when(mapper.insertLedger(any())).thenReturn(1);
        assertThat(service.process(1)).containsEntry("skippedCount",1);
        var ordered=inOrder(mapper,exchangeFunds);ordered.verify(exchangeFunds).begin(8L,"EX-GEO-HELD");ordered.verify(mapper).cancelQueuedBySystem("EX-GEO-HELD");
        ordered.verify(mapper).applyWalletDelta(8L,"USDT".equals(asset)?BigDecimal.TEN:BigDecimal.ZERO,"NEX".equals(asset)?BigDecimal.TEN:BigDecimal.ZERO);
        var ledger=org.mockito.ArgumentCaptor.forClass(AppExchangeMapper.LedgerWrite.class);ordered.verify(mapper).insertLedger(ledger.capture());
        assertThat(ledger.getValue().bizNo()).isEqualTo("EX-GEO-HELD-REFUND");assertThat(ledger.getValue().asset()).isEqualTo(asset);assertThat(ledger.getValue().amount()).isEqualByComparingTo("10");assertThat(ledger.getValue().balanceAfter()).isEqualByComparingTo("110");
        ordered.verify(exchangeFunds).recorded(any(),eq("EX-GEO-HELD"),eq("CANCEL"),eq(BigDecimal.ZERO));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"USDT","NEX"})
    void reservedCompletionCreditsOnlyTargetAndBindsAfterOriginalLedger(String asset){
        when(mapper.lockQueuedBatch(1)).thenReturn(List.of(new AppExchangeMapper.QueuedRow(8L,"EX-COMPLETE-HELD",asset,BigDecimal.TEN)));
        when(mapper.lockActiveUserNo(8L)).thenReturn("U00000008");when(mapper.lockWalletGate(8L)).thenReturn(new AppExchangeMapper.WalletGateRow(new BigDecimal("100"),new BigDecimal("100"),"VN"));
        when(mapper.sourceReservationExists("EX-COMPLETE-HELD")).thenReturn(1);when(mapper.applyWalletDelta(eq(8L),any(),any())).thenReturn(1);when(mapper.completeQueued(anyString(),any(),any())).thenReturn(1);when(mapper.insertLedger(any())).thenReturn(1);when(mapper.userAttribution(8L)).thenReturn(new AppExchangeMapper.UserAttribution("P1",1,"2026-W32"));
        assertThat(service.process(1)).containsEntry("completedCount",1);
        var ordered=inOrder(mapper,exchangeFunds);ordered.verify(exchangeFunds).begin(8L,"EX-COMPLETE-HELD");
        ordered.verify(mapper).applyWalletDelta(eq(8L),org.mockito.ArgumentMatchers.argThat(v->v.compareTo("USDT".equals(asset)?BigDecimal.ZERO:BigDecimal.TEN)==0),org.mockito.ArgumentMatchers.argThat(v->v.compareTo("NEX".equals(asset)?BigDecimal.ZERO:BigDecimal.TEN)==0));
        ordered.verify(mapper).completeQueued(eq("EX-COMPLETE-HELD"),any(),any());
        var ledger=org.mockito.ArgumentCaptor.forClass(AppExchangeMapper.LedgerWrite.class);ordered.verify(mapper).insertLedger(ledger.capture());assertThat(ledger.getValue().bizNo()).isEqualTo("EX-COMPLETE-HELD-IN");assertThat(ledger.getValue().direction()).isEqualTo("IN");
        verify(mapper,org.mockito.Mockito.times(1)).insertLedger(any());ordered.verify(exchangeFunds).recorded(any(),eq("EX-COMPLETE-HELD"),eq("COMPLETE"),eq(BigDecimal.ZERO));
    }
    private final AppExchangeMapper mapper = mock(AppExchangeMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final G2ExchangeFeeAllocationService feeAllocation = mock(G2ExchangeFeeAllocationService.class);
    private final Environment environment = mock(Environment.class);
    private final SupportExchangeFundAdapter exchangeFunds=mock(SupportExchangeFundAdapter.class);
    private final G2ExchangeQueueBatchService service =
            new G2ExchangeQueueBatchService(mapper, config, outbox, feeAllocation, environment,exchangeFunds);

    @BeforeEach
    void setUp() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.lockExchangeExecutionMutex()).thenReturn("G2_EXCHANGE_EXECUTION");
        when(mapper.currentPrice()).thenReturn(BigDecimal.ONE);
        when(mapper.platformTodayUsdt()).thenReturn(BigDecimal.ZERO);
        when(mapper.emergencyValue("killswitch.exchange")).thenReturn("enabled");
        when(mapper.emergencyValue("G2.killswitch.exchange")).thenReturn(null);
        when(mapper.countQueued()).thenReturn(0);
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "wallet.exchange.platform_daily_cap_usdt", "wallet.exchange.user_daily_cap_usdt" -> Optional.of("1000");
            case "wallet.exchange.fee_pct" -> Optional.of("0");
            case "wallet.exchange.fee_min_usdt" -> Optional.of("0.50");
            default -> Optional.empty();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsTruthfulCompletedSkippedAndFailedDetails() {
        when(mapper.lockQueuedBatch(3)).thenReturn(List.of(
                new AppExchangeMapper.QueuedRow(1L, "EX-1", "NEX", new BigDecimal("10")),
                new AppExchangeMapper.QueuedRow(2L, "EX-2", "NEX", new BigDecimal("10")),
                new AppExchangeMapper.QueuedRow(3L, "EX-3", "NEX", new BigDecimal("10"))));
        AppExchangeMapper.WalletGateRow wallet =
                new AppExchangeMapper.WalletGateRow(new BigDecimal("100"), new BigDecimal("100"), "VN");
        when(mapper.lockActiveUserNo(1L)).thenReturn("U00000001");
        when(mapper.lockActiveUserNo(2L)).thenReturn("U00000002");
        when(mapper.lockActiveUserNo(3L)).thenReturn("U00000003");
        when(mapper.lockWalletGate(1L)).thenReturn(wallet);
        when(mapper.lockWalletGate(2L)).thenReturn(null);
        when(mapper.lockWalletGate(3L)).thenReturn(wallet);
        when(mapper.userTodayUsdt(1L)).thenReturn(BigDecimal.ZERO);
        when(mapper.userTodayUsdt(3L)).thenReturn(BigDecimal.ZERO);
        when(mapper.applyWalletDelta(any(), any(), any())).thenAnswer(invocation ->
                Long.valueOf(1L).equals(invocation.getArgument(0)) ? 1 : 0);
        when(mapper.failQueuedInsufficient("EX-3")).thenReturn(1);
        when(mapper.completeQueued(anyString(), any(), any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(1L)).thenReturn(new AppExchangeMapper.UserAttribution("P1", 1, "2026-W32"));

        Map<String, Object> result = service.process(3);

        assertThat(result).containsEntry("requestedLimit", 3)
                .containsEntry("selectedCount", 3)
                .containsEntry("completedCount", 1)
                .containsEntry("skippedCount", 1)
                .containsEntry("failedCount", 1)
                .containsEntry("outcome", "PARTIAL");
        assertThat((List<Map<String, Object>>) result.get("completed"))
                .extracting(row -> row.get("exchangeNo")).containsExactly("EX-1");
        assertThat((List<Map<String, Object>>) result.get("skipped"))
                .singleElement().satisfies(row -> assertThat(row)
                        .containsEntry("exchangeNo", "EX-2")
                        .containsEntry("orderStatus", "QUEUED")
                        .containsEntry("reasonCode", "WALLET_UNAVAILABLE"));
        assertThat((List<Map<String, Object>>) result.get("failed"))
                .singleElement().satisfies(row -> assertThat(row)
                        .containsEntry("exchangeNo", "EX-3")
                        .containsEntry("orderStatus", "FAILED")
                        .containsEntry("reasonCode", "WALLET_BALANCE_CONFLICT"));
        verify(mapper).failQueuedInsufficient("EX-3");
        InOrder capOrder = inOrder(mapper);
        capOrder.verify(mapper).lockExchangeExecutionMutex();
        capOrder.verify(mapper).platformTodayUsdt();
        capOrder.verify(mapper).applyWalletDelta(any(), any(), any());
        var bindings=inOrder(mapper,exchangeFunds);
        bindings.verify(exchangeFunds).begin(1L,"EX-1");
        bindings.verify(mapper).applyWalletDelta(eq(1L),any(),any());
        bindings.verify(exchangeFunds).recorded(any(),eq("EX-1"),eq("COMPLETE"),eq(BigDecimal.ZERO));
        verify(exchangeFunds,never()).recorded(any(),eq("EX-3"),anyString(),any());
    }

    @Test
    void failsClosedBeforeSelectingProductionQueueInAnIsolatedProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(10))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(503))
                .hasMessageContaining("EXCHANGE_SANDBOX_ISOLATED_TABLE_UNAVAILABLE");
        verify(mapper, never()).lockQueuedBatch(any(Integer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsInactiveUserWithoutRollingBackTheBatch() {
        when(mapper.lockQueuedBatch(1)).thenReturn(List.of(
                new AppExchangeMapper.QueuedRow(9L, "EX-INACTIVE", "USDT", BigDecimal.TEN)));
        when(mapper.lockActiveUserNo(9L)).thenReturn(null);
        when(mapper.countQueued()).thenReturn(1);

        Map<String,Object> result = service.process(1);

        assertThat(result).containsEntry("outcome", "SKIPPED").containsEntry("skippedCount", 1);
        assertThat((List<Map<String,Object>>) result.get("skipped")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("reasonCode", "USER_INACTIVE")
                .containsEntry("orderStatus", "QUEUED"));
        verify(mapper, never()).lockWalletGate(9L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelsGeoBlockedQueueOrderWithoutWalletOrLedgerMutation() {
        when(mapper.lockQueuedBatch(1)).thenReturn(List.of(
                new AppExchangeMapper.QueuedRow(8L, "EX-GEO", "USDT", BigDecimal.TEN)));
        when(mapper.lockActiveUserNo(8L)).thenReturn("U00000008");
        when(mapper.lockWalletGate(8L)).thenReturn(
                new AppExchangeMapper.WalletGateRow(new BigDecimal("100"), new BigDecimal("100"), "KP"));
        when(mapper.userTodayUsdt(8L)).thenReturn(BigDecimal.ZERO);
        when(mapper.geoBlocked("KP")).thenReturn(1);
        when(mapper.cancelQueuedBySystem("EX-GEO")).thenReturn(1);

        Map<String,Object> result = service.process(1);

        assertThat((List<Map<String,Object>>) result.get("skipped")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("reasonCode", "GEO_BLOCKED")
                .containsEntry("orderStatus", "CANCELLED"));
        verify(mapper).cancelQueuedBySystem("EX-GEO");
        verify(mapper, never()).applyWalletDelta(any(), any(), any());
        verify(mapper, never()).insertLedger(any());
        var bindings=inOrder(mapper,exchangeFunds);
        bindings.verify(exchangeFunds).begin(8L,"EX-GEO");
        bindings.verify(mapper).cancelQueuedBySystem("EX-GEO");
        bindings.verify(exchangeFunds).recorded(any(),eq("EX-GEO"),eq("CANCEL"),eq(BigDecimal.ZERO));
    }

    @Test
    void reportsBusyInsteadOfEmptyWhenQueuedRowsAreLockedElsewhere() {
        when(mapper.lockQueuedBatch(50)).thenReturn(List.of());
        when(mapper.countQueued()).thenReturn(2);

        assertThat(service.process(50)).containsEntry("outcome", "BUSY")
                .containsEntry("selectedCount", 0)
                .containsEntry("remainingQueuedCount", 2);
    }

    @Test
    void completesReservedQueueOrderWithoutDebitingSourceTwice() {
        when(mapper.lockQueuedBatch(1)).thenReturn(List.of(
                new AppExchangeMapper.QueuedRow(1L, "EX-RESERVED", "USDT", BigDecimal.TEN)));
        when(mapper.lockActiveUserNo(1L)).thenReturn("U00000001");
        when(mapper.lockWalletGate(1L)).thenReturn(
                new AppExchangeMapper.WalletGateRow(new BigDecimal("90"), new BigDecimal("100"), "VN"));
        when(mapper.sourceReservationExists("EX-RESERVED")).thenReturn(1);
        when(mapper.userTodayUsdt(1L)).thenReturn(BigDecimal.ZERO);
        when(mapper.completeQueued(anyString(), any(), any())).thenReturn(1);
        when(mapper.applyWalletDelta(any(), any(), any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(1L)).thenReturn(new AppExchangeMapper.UserAttribution("P1", 1, "2026-W32"));

        assertThat(service.process(1)).containsEntry("completedCount", 1);

        verify(mapper).applyWalletDelta(eq(1L),
                org.mockito.ArgumentMatchers.argThat((BigDecimal value) -> value.compareTo(BigDecimal.ZERO) == 0),
                org.mockito.ArgumentMatchers.argThat((BigDecimal value) -> value.compareTo(BigDecimal.TEN) == 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rechecksKillSwitchBeforeEveryQueuedOrder() {
        executableRows("EX-KILL-1", "EX-KILL-2");
        when(mapper.emergencyValue("killswitch.exchange")).thenReturn("enabled", "enabled", "disabled");

        Map<String,Object> result = service.process(2);

        assertThat(result).containsEntry("completedCount", 1).containsEntry("skippedCount", 1)
                .containsEntry("outcome", "PARTIAL");
        assertThat((List<Map<String,Object>>) result.get("skipped")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("exchangeNo", "EX-KILL-2")
                .containsEntry("reasonCode", "EXCHANGE_SWAP_PAUSED")
                .containsEntry("orderStatus", "QUEUED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rechecksTightenedPlatformCapBeforeEveryQueuedOrder() {
        executableRows("EX-CAP-1", "EX-CAP-2");
        AtomicInteger platformCapReads = new AtomicInteger();
        when(config.activeValue(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("wallet.exchange.platform_daily_cap_usdt".equals(key)) {
                return Optional.of(platformCapReads.getAndIncrement() == 0 ? "1000" : "15");
            }
            if ("wallet.exchange.user_daily_cap_usdt".equals(key)) return Optional.of("1000");
            if ("wallet.exchange.fee_pct".equals(key)) return Optional.of("0");
            if ("wallet.exchange.fee_min_usdt".equals(key)) return Optional.of("0.50");
            return Optional.empty();
        });

        Map<String,Object> result = service.process(2);

        assertThat(result).containsEntry("completedCount", 1).containsEntry("skippedCount", 1)
                .containsEntry("outcome", "PARTIAL");
        assertThat((List<Map<String,Object>>) result.get("skipped")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("exchangeNo", "EX-CAP-2")
                .containsEntry("reasonCode", "PLATFORM_CAP_EXCEEDED"));
        assertThat(platformCapReads).hasValue(2);
    }

    private void executableRows(String firstExchangeNo, String secondExchangeNo) {
        when(mapper.lockQueuedBatch(2)).thenReturn(List.of(
                new AppExchangeMapper.QueuedRow(1L, firstExchangeNo, "USDT", BigDecimal.TEN),
                new AppExchangeMapper.QueuedRow(2L, secondExchangeNo, "USDT", BigDecimal.TEN)));
        AppExchangeMapper.WalletGateRow wallet =
                new AppExchangeMapper.WalletGateRow(new BigDecimal("100"), new BigDecimal("100"), "VN");
        when(mapper.lockActiveUserNo(1L)).thenReturn("U00000001");
        when(mapper.lockActiveUserNo(2L)).thenReturn("U00000002");
        when(mapper.lockWalletGate(1L)).thenReturn(wallet);
        when(mapper.lockWalletGate(2L)).thenReturn(wallet);
        when(mapper.userTodayUsdt(1L)).thenReturn(BigDecimal.ZERO);
        when(mapper.userTodayUsdt(2L)).thenReturn(BigDecimal.ZERO);
        when(mapper.applyWalletDelta(any(), any(), any())).thenReturn(1);
        when(mapper.completeQueued(anyString(), any(), any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(1L)).thenReturn(new AppExchangeMapper.UserAttribution("P1", 1, "2026-W32"));
        when(mapper.userAttribution(2L)).thenReturn(new AppExchangeMapper.UserAttribution("P1", 1, "2026-W32"));
    }
}
