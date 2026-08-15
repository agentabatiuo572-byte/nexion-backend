package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AppOrderCommandServiceTest {
    @Test
    void cancelsOnlyPendingOwnedProductionOrderAndReturnsBundleStockAtomically() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var audit = mock(AuditLogService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("BND-1")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "BND-1", 7L, 1L, 2, "BUNDLE", "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockItems("BND-1")).thenReturn(List.of(
                new AppOrderCommandMapper.ItemRow("BND-1", 1L, "S1", 1),
                new AppOrderCommandMapper.ItemRow("BND-1", 2L, "PRO", 1)));
        when(mapper.lockProduct(1L)).thenReturn(new AppOrderCommandMapper.ProductRow(1L, 0, 1));
        when(mapper.lockProduct(2L)).thenReturn(new AppOrderCommandMapper.ProductRow(2L, 0, 1));
        when(mapper.returnStock(1L, 1)).thenReturn(1);
        when(mapper.returnStock(2L, 1)).thenReturn(1);
        when(mapper.cancelOrder("BND-1", 7L)).thenReturn(1);

        var result = new AppOrderCommandService(mapper, idempotency, audit, guard, null, null, null)
                .cancel(7L, "BND-1", "cancel-key-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderNo", "BND-1").containsEntry("orderStatus", "CANCELLED");
        verify(mapper).returnStock(1L, 1);
        verify(mapper).returnStock(2L, 1);
        verify(audit).recordRequired(any());
    }

    @Test
    void rejectsOtherOwnerAndPaidOrderWithoutMutatingInventory() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("ORD-PAID")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "ORD-PAID", 9L, 1L, 1, "SINGLE", "PAID", "PAID", "WAITING_PROVISIONING"));
        var service = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard, null, null, null);
        assertThat(service.cancel(7L, "ORD-PAID", "cancel-key-2").getMessage()).isEqualTo("ORDER_FORBIDDEN");
        verify(mapper, never()).returnStock(anyLong(), anyInt());
    }
}
