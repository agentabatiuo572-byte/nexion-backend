package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AppBundleOrderServiceTest {
    @Test
    void pricesAndPersistsOneServerAuthoritativeBundle() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "stellarbox-s1", "S1", new BigDecimal("100"), 2),
                new AppBundleOrderMapper.ProductRow(2L, "stellarbox-pro", "Pro", new BigDecimal("200"), 2)));
        when(mapper.deviceSlotCap()).thenReturn(6);
        when(mapper.attribution(7L)).thenReturn(new AppBundleOrderMapper.Attribution("P1", 1, "2026-W33"));
        when(mapper.decrementStock(anyLong())).thenReturn(1);
        when(mapper.insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), any(), any(), any())).thenReturn(1);
        when(mapper.insertBundleItem(anyString(), any(), anyInt())).thenReturn(1);

        var result = new AppBundleOrderService(mapper, idempotency, outbox, guard,
                new StorefrontPurchaseGatePolicy())
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), "bundle-key-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderType", "BUNDLE")
                .containsEntry("subtotalUsdt", new BigDecimal("300.000000"))
                .containsEntry("discountUsdt", new BigDecimal("15.000000"))
                .containsEntry("amountUsdt", new BigDecimal("285.000000"));
        verify(mapper, times(2)).insertBundleItem(anyString(), any(), anyInt());
    }

    @Test
    void strictSandboxFailsBeforeAnyCanonicalWrite() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        var result = new AppBundleOrderService(mapper, mock(AdminIdempotencyService.class),
                mock(EventOutboxService.class), guard, new StorefrontPurchaseGatePolicy())
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), "bundle-key-1");
        assertThat(result.getMessage()).isEqualTo("BUNDLE_CHECKOUT_SANDBOX_UNSUPPORTED");
        verify(mapper, never()).lockUser(anyLong());
    }

    @Test
    void bundleAppliesServerPurchaseGateToEveryLine() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100"), 2,
                        "{\"rankMin\":3,\"mode\":\"all\",\"enforce\":true}"),
                new AppBundleOrderMapper.ProductRow(2L, "pro", "Pro", new BigDecimal("200"), 2, null)));
        when(mapper.purchaseFacts(7L)).thenReturn(new AppBundleOrderMapper.PurchaseFacts(2, 0, BigDecimal.ZERO));
        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy())
                .create(7L, List.of("s1", "pro"), "bundle-gate-key");
        assertThat(result.getMessage()).isEqualTo("PURCHASE_GATE_BLOCKED");
        verify(mapper, never()).decrementStock(anyLong());
    }
}
