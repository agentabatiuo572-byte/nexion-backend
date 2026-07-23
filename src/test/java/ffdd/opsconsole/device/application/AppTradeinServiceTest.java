package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.dto.AppTradeinQuoteRequest;
import ffdd.opsconsole.device.dto.AppTradeinSubmitRequest;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppTradeinServiceTest {
    private final AppTradeinMapper mapper = mock(AppTradeinMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AppTradeinService service = new AppTradeinService(mapper, idempotency, outbox, audit);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(mapper.listTradeinConfig()).thenReturn(validConfig());
        when(mapper.findSourceDevice(7L, 11L)).thenReturn(source());
        when(mapper.findTargetProduct(22L, null)).thenReturn(target());
        when(mapper.lockSourceDevice(7L, 11L)).thenReturn(source());
        when(mapper.lockTargetProduct(22L, null)).thenReturn(target());
        when(mapper.userLevel(7L)).thenReturn("L4");
        when(mapper.cumulativeDeviceOutputUsdt(11L)).thenReturn(new BigDecimal("400.00"));
        when(mapper.walletBalanceUsdt(7L)).thenReturn(new BigDecimal("1000.00"));
        when(mapper.lockWalletBalanceUsdt(7L)).thenReturn(new BigDecimal("1000.00"));
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.userEventAttribution(7L)).thenReturn(
                new AppTradeinMapper.UserEventAttribution("P3", 8, "2026-W30"));
    }

    @Test
    void quoteUsesSettledOutputToSelectTheServerLadder() {
        var result = service.quote(7L, new AppTradeinQuoteRequest(11L, 22L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().outputRatioPct()).isEqualByComparingTo("40.000000");
        assertThat(result.getData().creditRatePct()).isEqualByComparingTo("60");
        assertThat(result.getData().discountUsdt()).isEqualByComparingTo("600.000000");
        assertThat(result.getData().payableUsdt()).isEqualByComparingTo("900.000000");
        assertThat(result.getData().discountToWallet()).isFalse();
    }

    @Test
    void quoteRejectsTargetThatIsNotMoreExpensiveWhenTheCanonicalGateIsEnabled() {
        when(mapper.findTargetProduct(22L, null)).thenReturn(new AppTradeinMapper.TargetProduct(
                22L, "SKU-SAME", "Same", "PRO", "ACTIVE", new BigDecimal("1000"), 3,
                "BOX", 2, "GPU", 48, new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("3")));

        assertThatThrownBy(() -> service.quote(7L, new AppTradeinQuoteRequest(11L, 22L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TRADEIN_HIGHER_PRICE_REQUIRED");
    }

    @Test
    void quoteAcceptsTheStableStringSkuUsedByTheUniappCatalog() {
        when(mapper.findTargetProduct(null, "stellarbox-pro-v2")).thenReturn(target());

        var result = service.quote(7L, new AppTradeinQuoteRequest(11L, null, "stellarbox-pro-v2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().targetProductNo()).isEqualTo("SKU-NEW");
        verify(mapper).findTargetProduct(null, "stellarbox-pro-v2");
    }

    @Test
    void submitAtomicallyDebitsWalletPostsD4LedgerRecyclesAndDelivers() {
        when(mapper.debitWalletUsdt(7L, new BigDecimal("900.000000"))).thenReturn(1);
        when(mapper.insertWalletLedger(anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.decrementTargetStock(22L)).thenReturn(1);
        when(mapper.recycleSourceDevice(7L, 11L)).thenReturn(1);
        when(mapper.insertTargetDevice(any())).thenReturn(1);
        when(mapper.findDeviceIdByInstanceNo(anyString())).thenReturn(33L);
        when(mapper.insertTradeinApplication(any())).thenReturn(1);
        when(mapper.insertTradeinCompatibilityOrder(any())).thenReturn(1);
        when(mapper.insertPaidOrder(any())).thenReturn(1);
        when(mapper.insertPaidOrderItem(any())).thenReturn(1);

        ApiResult<?> result = service.submit(7L, "idem-7", new AppTradeinSubmitRequest(11L, 22L));

        assertThat(result.getCode()).isZero();
        verify(mapper).debitWalletUsdt(7L, new BigDecimal("900.000000"));
        verify(mapper).insertWalletLedger(anyString(), any(), any(), any());
        verify(mapper).recycleSourceDevice(7L, 11L);
        verify(mapper).insertTargetDevice(any());
        verify(mapper).insertTradeinApplication(any());
        verify(mapper).insertPaidOrder(any());
        verify(mapper).insertPaidOrderItem(any());
        verify(outbox).publishUserEvent(anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void zeroPayableTradeinStillPostsAZeroAmountD4TraceWithoutDebitingWallet() {
        when(mapper.listTradeinConfig()).thenReturn(List.of(
                row("tradeinEnabled", "true"), row("eligibility", "L2+ 持有者"),
                row("tradeinLadderCut1", "25"), row("tradeinLadderCut2", "50"),
                row("tradeinLadderCut3", "75"), row("tradeinLadderCut4", "100"),
                row("tradeinLadderCredit1", "100"), row("tradeinLadderCredit2", "90"),
                row("tradeinLadderCredit3", "80"), row("tradeinLadderCredit4", "70"),
                row("tradeinLadderCredit5", "60"), row("tradeinRequireHigherPrice", "false"),
                row("tradeinMaxDevicesPerOrder", "1")));
        when(mapper.cumulativeDeviceOutputUsdt(11L)).thenReturn(new BigDecimal("100.00"));
        var samePriceTarget = new AppTradeinMapper.TargetProduct(
                22L, "SKU-SAME", "Same", "PRO", "ACTIVE", new BigDecimal("1000"), 3,
                "BOX", 2, "GPU", 48, new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("3"));
        when(mapper.lockTargetProduct(22L, null)).thenReturn(samePriceTarget);
        when(mapper.insertWalletLedger(anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.decrementTargetStock(22L)).thenReturn(1);
        when(mapper.recycleSourceDevice(7L, 11L)).thenReturn(1);
        when(mapper.insertTargetDevice(any())).thenReturn(1);
        when(mapper.findDeviceIdByInstanceNo(anyString())).thenReturn(33L);
        when(mapper.insertTradeinApplication(any())).thenReturn(1);
        when(mapper.insertTradeinCompatibilityOrder(any())).thenReturn(1);
        when(mapper.insertPaidOrder(any())).thenReturn(1);
        when(mapper.insertPaidOrderItem(any())).thenReturn(1);

        ApiResult<?> result = service.submit(7L, "idem-zero", new AppTradeinSubmitRequest(11L, 22L));

        assertThat(result.getCode()).isZero();
        verify(mapper, never()).debitWalletUsdt(any(), any());
        verify(mapper).insertWalletLedger(anyString(), any(),
                org.mockito.ArgumentMatchers.argThat(amount -> new BigDecimal("0.000000").compareTo(amount) == 0),
                org.mockito.ArgumentMatchers.argThat(balance -> new BigDecimal("1000.000000").compareTo(balance) == 0));
    }

    @Test
    void insufficientFundsFailsBeforeAnyBusinessWrite() {
        when(mapper.lockWalletBalanceUsdt(7L)).thenReturn(new BigDecimal("899.99"));

        assertThatThrownBy(() -> service.submit(7L, "idem-low", new AppTradeinSubmitRequest(11L, 22L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TRADEIN_INSUFFICIENT_FUNDS");
        verify(mapper, never()).debitWalletUsdt(any(), any());
        verify(mapper, never()).recycleSourceDevice(any(), any());
        verify(mapper, never()).insertTradeinApplication(any());
    }

    private List<AppTradeinMapper.ConfigRow> validConfig() {
        return List.of(
                row("tradeinEnabled", "true"), row("eligibility", "L2+ 持有者"),
                row("tradeinLadderCut1", "25"), row("tradeinLadderCut2", "50"),
                row("tradeinLadderCut3", "75"), row("tradeinLadderCut4", "100"),
                row("tradeinLadderCredit1", "75"), row("tradeinLadderCredit2", "60"),
                row("tradeinLadderCredit3", "45"), row("tradeinLadderCredit4", "30"),
                row("tradeinLadderCredit5", "15"), row("tradeinRequireHigherPrice", "true"),
                row("tradeinMaxDevicesPerOrder", "1"));
    }

    private AppTradeinMapper.ConfigRow row(String key, String value) {
        return new AppTradeinMapper.ConfigRow(key, value);
    }

    private AppTradeinMapper.SourceDevice source() {
        return new AppTradeinMapper.SourceDevice(
                11L, 7L, "DEV-11", 5L, "SKU-OLD", "Old", "S1", "ACTIVE",
                new BigDecimal("1000.00"));
    }

    private AppTradeinMapper.TargetProduct target() {
        return new AppTradeinMapper.TargetProduct(
                22L, "SKU-NEW", "New", "PRO", "ACTIVE", new BigDecimal("1500.00"), 3,
                "BOX", 2, "GPU", 48, new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("3"));
    }
}
