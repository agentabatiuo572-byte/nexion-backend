package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
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
                "BND-1", 7L, 1L, 2, "BUNDLE", 2, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
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
                "ORD-PAID", 9L, 1L, 1, "SINGLE", 1, "PAID", "PAID", "WAITING_PROVISIONING"));
        var service = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard, null, null, null);
        assertThat(service.cancel(7L, "ORD-PAID", "cancel-key-2").getMessage()).isEqualTo("ORDER_FORBIDDEN");
        verify(mapper, never()).returnStock(anyLong(), anyInt());
    }

    @Test
    void rejectsBundleWhenItemCountOrProductSetDoesNotMatchBeforeReturningStock() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("BND-MISMATCH")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "BND-MISMATCH", 7L, 1L, 2, "BUNDLE", 3, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockItems("BND-MISMATCH")).thenReturn(List.of(
                new AppOrderCommandMapper.ItemRow("BND-MISMATCH", 1L, "S1", 1),
                new AppOrderCommandMapper.ItemRow("BND-MISMATCH", 1L, "S1", 1)));

        var service = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard, null, null, null);

        assertThatThrownBy(() -> service.cancel(7L, "BND-MISMATCH", "cancel-key-mismatch"))
                .hasMessageContaining("ORDER_ITEM_SNAPSHOT_CONFLICT");
        verify(mapper, never()).returnStock(anyLong(), anyInt());
        verify(mapper, never()).cancelOrder(anyString(), anyLong());
    }

    @Test
    void sandboxCancelEventIdIsScopedByRunUserOrderAndKey() {
        var mapper = mock(CommerceAcceptanceSandboxMapper.class);
        var sandboxService = mock(CommerceAcceptanceSandboxService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var run = mock(CommerceAcceptanceRun.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.isSandboxUser(7L)).thenReturn(true);
        when(mapper.isSandboxUser(8L)).thenReturn(true);
        when(run.requireRunId()).thenReturn("run-20260815", "run-20260815", "run-20260815", "run-20260816");
        when(mapper.lockSandboxOrder(anyString(), anyString())).thenAnswer(invocation ->
                new CommerceAcceptanceSandboxMapper.SandboxOrder(invocation.getArgument(1),
                        invocation.getArgument(1, String.class).equals("ORD-A") && invocation.getArgument(0, String.class).equals("run-20260816") ? 8L : 7L, 1L,
                        1, BigDecimal.ONE, 0L, "PENDING_PAYMENT", false, false));
        when(sandboxService.applyCallback(anyString(), anyString(), eq("USER_CANCELLED"), eq(0L), anyString(), anyString()))
                .thenAnswer(invocation -> new CommerceAcceptanceSandboxService.CallbackResult(
                        invocation.getArgument(0), "USER_CANCELLED", "cancelled", 1L, "mock", "SANDBOX", BigDecimal.ZERO));
        var service = new AppOrderCommandService(mock(AppOrderCommandMapper.class), mock(AdminIdempotencyService.class),
                mock(AuditLogService.class), guard, mapper, sandboxService, run);

        service.cancel(7L, "ORD-A", "same-key");
        service.cancel(7L, "ORD-A", "same-key");
        service.cancel(7L, "ORD-B", "same-key");
        service.cancel(8L, "ORD-A", "same-key");

        var eventIds = ArgumentCaptor.forClass(String.class);
        verify(sandboxService, times(4)).applyCallback(anyString(), eventIds.capture(), eq("USER_CANCELLED"),
                eq(0L), anyString(), anyString());
        assertThat(eventIds.getAllValues().get(0)).isEqualTo(eventIds.getAllValues().get(1));
        assertThat(eventIds.getAllValues().get(0)).isNotEqualTo(eventIds.getAllValues().get(2));
        assertThat(eventIds.getAllValues().get(0)).isNotEqualTo(eventIds.getAllValues().get(3));
        assertThat(eventIds.getAllValues().get(0)).startsWith("USER-CANCEL-");
    }

    @Test
    void sandboxCancelRejectsOversizedIdempotencyKeyBeforeOrderMutation() {
        var mapper = mock(CommerceAcceptanceSandboxMapper.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.isSandboxUser(7L)).thenReturn(true);
        var service = new AppOrderCommandService(mock(AppOrderCommandMapper.class), mock(AdminIdempotencyService.class),
                mock(AuditLogService.class), guard, mapper, mock(CommerceAcceptanceSandboxService.class), mock(CommerceAcceptanceRun.class));

        assertThat(service.cancel(7L, "ORD-A", "x".repeat(129)).getMessage())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
        verify(mapper, never()).lockSandboxOrder(anyString(), anyString());
    }
}
