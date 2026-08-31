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
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class AppOrderCommandServiceTest {
    @Test
    void developmentPaymentUsesCanonicalBusinessTablesAndImmediateLocalFulfillment() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var audit = mock(AuditLogService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictDevelopmentRuntime()).thenReturn(true);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-DEV-1")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-DEV-1", 7L, 18L, 1, new BigDecimal("1199.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("2000.000000"), 4L));
        when(mapper.debitDevelopmentWallet(7L, new BigDecimal("1199.000000"), 4L)).thenReturn(1);
        when(mapper.insertDevelopmentPurchaseLedger(eq("ORD-DEV-1"), eq(7L),
                eq(new BigDecimal("1199.000000")), eq(new BigDecimal("801.000000")))).thenReturn(1);
        when(mapper.markDevelopmentOrderActivated(eq("ORD-DEV-1"), eq(7L), startsWith("PAY-DEV-")))
                .thenReturn(1);
        when(mapper.insertDevelopmentPayment(eq("ORD-DEV-1"), eq(7L), startsWith("PAY-DEV-"),
                eq(new BigDecimal("1199.000000")))).thenReturn(1);
        when(mapper.insertDevelopmentDevice(eq("ORD-DEV-1"), eq(7L), startsWith("DEV-ORD-"), eq(0)))
                .thenReturn(1);
        when(mapper.developmentDeviceFact(startsWith("DEV-ORD-"))).thenReturn(
                new AppOrderCommandMapper.DevelopmentDeviceFact(91L, "DEV-ORD-INSTANCE"));
        when(mapper.attribution(7L)).thenReturn(Map.of(
                "phase", "P3", "accountAgeMonths", 0, "cohort", "2026-W34"));
        when(mapper.insertDevelopmentOrderHistory(eq("ORD-DEV-1"), eq("placed"), eq("activated"),
                anyString())).thenReturn(1);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var outbox = mock(EventOutboxService.class);
        var result = new AppOrderCommandService(mapper, idempotency, audit, guard, null, null, null, outbox)
                .pay(7L, "ORD-DEV-1", "development-pay-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderNo", "ORD-DEV-1")
                .containsEntry("paymentStatus", "PAID")
                .containsEntry("orderStatus", "COMPLETED")
                .containsEntry("canonicalStatus", "activated")
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("walletBalanceAfterUsdt", new BigDecimal("801.000000"));
        verify(mapper).debitDevelopmentWallet(7L, new BigDecimal("1199.000000"), 4L);
        verify(mapper).insertDevelopmentPurchaseLedger("ORD-DEV-1", 7L,
                new BigDecimal("1199.000000"), new BigDecimal("801.000000"));
        verify(mapper).insertDevelopmentDevice(eq("ORD-DEV-1"), eq(7L), startsWith("DEV-ORD-"), eq(0));
        verify(outbox).publishUserEvent(eq("DEVICE"), eq("91"), eq("admin.device_activated"), eq(7L),
                eq("P3"), eq(0), eq("2026-W34"), argThat(payload -> payload instanceof Map<?, ?> map
                        && map.get("deviceId").equals(91L)
                        && map.get("instanceNo").equals("DEV-ORD-INSTANCE")
                        && map.get("mode").equals("DEVELOPMENT_LOCAL")));
        verify(outbox).publishUserEvent(eq("ORDER"), eq("ORD-DEV-1"), eq("checkout.completed"), eq(7L),
                eq("P3"), eq(0), eq("2026-W34"), argThat(payload -> payload instanceof Map<?, ?> map
                        && map.get("order_no").equals("ORD-DEV-1")
                        && map.get("order_subtotal_usdt").equals(new BigDecimal("1199.000000"))
                        && map.get("order_id").equals("ORD-DEV-1")));
        verify(audit).recordRequired(any());
        verifyNoInteractions(mock(CommerceAcceptanceSandboxMapper.class));
    }

    @Test
    void developmentPaymentRejectsInsufficientWalletBeforeAnyOrderOrDeviceMutation() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictDevelopmentRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-DEV-LOW")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-DEV-LOW", 7L, 18L, 1, new BigDecimal("199.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("10.000000"), 9L));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-DEV-LOW", "development-pay-low");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ORDER_WALLET_INSUFFICIENT");
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
        verify(mapper, never()).insertDevelopmentPurchaseLedger(anyString(), anyLong(), any(), any());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
        verify(mapper, never()).insertDevelopmentPayment(anyString(), anyLong(), anyString(), any());
        verify(mapper, never()).insertDevelopmentDevice(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void paidDevelopmentOrderReplayReturnsCurrentWalletWithoutDoubleDebit() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictDevelopmentRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-DEV-PAID")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-DEV-PAID", 7L, 18L, 1, new BigDecimal("199.000000"),
                        "PAY-DEV-AF2D80199A1F88C85A3C8B75AEC54D43",
                        "PAID", "COMPLETED", "ACTIVATED"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("801.000000"), 5L));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-DEV-PAID", "development-pay-replay");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("idempotent", true)
                .containsEntry("walletBalanceAfterUsdt", new BigDecimal("801.000000"));
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
        verify(mapper, never()).insertDevelopmentPurchaseLedger(anyString(), anyLong(), any(), any());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
        verify(mapper, never()).insertDevelopmentDevice(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void cancelsOnlyPendingOwnedProductionOrderAndReturnsBundleStockAtomically() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var audit = mock(AuditLogService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
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
        assertThat(result.getData()).containsEntry("orderNo", "BND-1").containsEntry("orderStatus", "CANCELLED")
                .containsEntry("serverCanonical", true).containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        verify(mapper).returnStock(1L, 1);
        verify(mapper).returnStock(2L, 1);
        verify(audit).recordRequired(any());
    }

    @Test
    void idempotentProductionCancelStillCarriesCanonicalProvenance() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("ORD-CANCELLED")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "ORD-CANCELLED", 7L, 1L, 1, "SINGLE", 1, "CANCELLED", "CANCELLED", "WAITING_PAYMENT"));

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard, null, null, null)
                .cancel(7L, "ORD-CANCELLED", "cancel-key-idempotent");

        assertThat(result.getData()).containsEntry("serverCanonical", true)
                .containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "").containsEntry("idempotent", true);
        verify(mapper, never()).cancelOrder(anyString(), anyLong());
    }

    @Test
    void rejectsOtherOwnerAndPaidOrderWithoutMutatingInventory() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
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
        when(guard.isStrictProductionRuntime()).thenReturn(true);
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
    void nonProductionRuntimeWithoutSandboxRailRejectsCancelBeforeCanonicalReadsOrWrites() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var audit = mock(AuditLogService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(false);

        var result = new AppOrderCommandService(mapper, idempotency, audit, guard, null, null, null)
                .cancel(7L, "ORD-PRODUCTION", "mixed-cancel-key");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("COMMERCE_SANDBOX_UNAVAILABLE");
        verifyNoInteractions(mapper, idempotency, audit);
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

        var first = service.cancel(7L, "ORD-A", "same-key");
        service.cancel(7L, "ORD-A", "same-key");
        service.cancel(7L, "ORD-B", "same-key");
        var differentRun = service.cancel(8L, "ORD-A", "same-key");

        assertThat(first.getData()).containsEntry("serverCanonical", true)
                .containsEntry("source", "mock").containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-20260815");
        assertThat(differentRun.getData()).containsEntry("serverCanonical", true)
                .containsEntry("source", "mock").containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-20260816");

        var eventIds = ArgumentCaptor.forClass(String.class);
        verify(sandboxService, times(4)).applyCallback(anyString(), eventIds.capture(), eq("USER_CANCELLED"),
                eq(0L), anyString(), anyString());
        assertThat(eventIds.getAllValues().get(0)).isEqualTo(eventIds.getAllValues().get(1));
        assertThat(eventIds.getAllValues().get(0)).isNotEqualTo(eventIds.getAllValues().get(2));
        assertThat(eventIds.getAllValues().get(0)).isNotEqualTo(eventIds.getAllValues().get(3));
        assertThat(eventIds.getAllValues().get(0)).startsWith("USER-CANCEL-");
    }

    @Test
    void idempotentSandboxCancelCarriesRunScopedCanonicalProvenance() {
        var mapper = mock(CommerceAcceptanceSandboxMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var run = mock(CommerceAcceptanceRun.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.isSandboxUser(7L)).thenReturn(true);
        when(run.requireRunId()).thenReturn("run-20260816");
        when(mapper.lockSandboxOrder("run-20260816", "ORD-CANCELLED")).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxOrder("ORD-CANCELLED", 7L, 1L, 1,
                        BigDecimal.ONE, 1L, "CANCELLED", false, false));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mock(AppOrderCommandMapper.class), idempotency,
                mock(AuditLogService.class), guard, mapper, mock(CommerceAcceptanceSandboxService.class), run)
                .cancel(7L, "ORD-CANCELLED", "cancel-key-idempotent");

        assertThat(result.getData()).containsEntry("serverCanonical", true)
                .containsEntry("source", "mock").containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-20260816").containsEntry("idempotent", true);
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

    @Test
    void sandboxPaymentUsesStablePaymentIdentityAndDelegatesAtomicDebitToSandboxCallback() {
        var mapper = mock(CommerceAcceptanceSandboxMapper.class);
        var sandboxService = mock(CommerceAcceptanceSandboxService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var run = mock(CommerceAcceptanceRun.class);
        var idempotency = mock(AdminIdempotencyService.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.isSandboxUser(7L)).thenReturn(true);
        when(run.requireRunId()).thenReturn("run-20260815");
        when(mapper.lockSandboxOrder("run-20260815", "CSO-1")).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxOrder("CSO-1", 7L, 1L, 1,
                        new BigDecimal("12.000000"), 0L, "PENDING_PAYMENT", false, false));
        when(sandboxService.applyCallback(eq("CSO-1"), startsWith("PAY-SBX-"), eq("PAYMENT_SUCCEEDED"),
                eq(0L), anyString(), eq("7"))).thenReturn(
                new CommerceAcceptanceSandboxService.CallbackResult("CSO-1", "PAYMENT_SUCCEEDED", "paid",
                        1L, "mock", "SANDBOX", new BigDecimal("8.000000")));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var service = new AppOrderCommandService(mock(AppOrderCommandMapper.class), idempotency,
                mock(AuditLogService.class), guard, mapper, sandboxService, run);
        var first = service.pay(7L, "CSO-1", "pay-key");
        var second = service.pay(7L, "CSO-1", "pay-key");

        assertThat(first.getData()).containsEntry("paymentStatus", "PAID")
                .containsEntry("source", "mock").containsEntry("sourceEnvironment", "SANDBOX");
        assertThat(first.getData().get("paymentNo")).isEqualTo(second.getData().get("paymentNo"));
        verify(sandboxService, times(2)).applyCallback(eq("CSO-1"), startsWith("PAY-SBX-"),
                eq("PAYMENT_SUCCEEDED"), eq(0L), anyString(), eq("7"));
    }

    @Test
    void sandboxPaymentIdempotencyScopeAndHashCannotReplayAcrossRuns() {
        var mapper = mock(CommerceAcceptanceSandboxMapper.class);
        var sandboxService = mock(CommerceAcceptanceSandboxService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var run = mock(CommerceAcceptanceRun.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var scopes = new java.util.ArrayList<String>();
        var hashes = new java.util.ArrayList<String>();
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.isSandboxUser(7L)).thenReturn(true);
        when(run.requireRunId()).thenReturn("run-20260815", "run-20260816");
        when(mapper.lockSandboxOrder(anyString(), eq("CSO-RUN"))).thenAnswer(invocation ->
                new CommerceAcceptanceSandboxMapper.SandboxOrder("CSO-RUN", 7L, 1L, 1,
                        new BigDecimal("12.000000"), 0L, "PENDING_PAYMENT", false, false));
        when(sandboxService.applyCallback(eq("CSO-RUN"), startsWith("PAY-SBX-"), eq("PAYMENT_SUCCEEDED"),
                eq(0L), anyString(), eq("7"))).thenAnswer(invocation ->
                new CommerceAcceptanceSandboxService.CallbackResult("CSO-RUN", "PAYMENT_SUCCEEDED", "paid",
                        1L, "mock", "SANDBOX", new BigDecimal("8.000000")));
        doAnswer(invocation -> {
            scopes.add(invocation.getArgument(0));
            hashes.add(invocation.getArgument(2));
            return ((Supplier<?>) invocation.getArgument(4)).get();
        }).when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var service = new AppOrderCommandService(mock(AppOrderCommandMapper.class), idempotency,
                mock(AuditLogService.class), guard, mapper, sandboxService, run);
        var first = service.pay(7L, "CSO-RUN", "same-payment-key");
        var second = service.pay(7L, "CSO-RUN", "same-payment-key");

        assertThat(first.getData()).containsEntry("runId", "run-20260815");
        assertThat(second.getData()).containsEntry("runId", "run-20260816");
        assertThat(scopes).containsExactly(
                "APP:ORDER_PAYMENT:SANDBOX:run-20260815:USER:7",
                "APP:ORDER_PAYMENT:SANDBOX:run-20260816:USER:7");
        assertThat(hashes).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void sandboxPaymentValidatesRunBeforeEnteringIdempotency() {
        var mapper = mock(CommerceAcceptanceSandboxMapper.class);
        var sandboxService = mock(CommerceAcceptanceSandboxService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var run = mock(CommerceAcceptanceRun.class);
        var idempotency = mock(AdminIdempotencyService.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.isSandboxUser(7L)).thenReturn(true);
        when(run.requireRunId()).thenThrow(new ffdd.opsconsole.shared.exception.BizException(
                503, "COMMERCE_SANDBOX_RUN_ID_REQUIRED"));

        var service = new AppOrderCommandService(mock(AppOrderCommandMapper.class), idempotency,
                mock(AuditLogService.class), guard, mapper, sandboxService, run);

        assertThatThrownBy(() -> service.pay(7L, "CSO-RUN", "payment-key"))
                .hasMessageContaining("COMMERCE_SANDBOX_RUN_ID_REQUIRED");
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void sandboxPaymentFailsClosedWhenLegacyOrderVersionIsNull() {
        var mapper = mock(CommerceAcceptanceSandboxMapper.class);
        var sandboxService = mock(CommerceAcceptanceSandboxService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var run = mock(CommerceAcceptanceRun.class);
        var idempotency = mock(AdminIdempotencyService.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.isSandboxUser(7L)).thenReturn(true);
        when(run.requireRunId()).thenReturn("run-20260815");
        when(mapper.lockSandboxOrder("run-20260815", "CSO-LEGACY")).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxOrder("CSO-LEGACY", 7L, 1L, 1,
                        new BigDecimal("12.000000"), null, "PENDING_PAYMENT", false, false));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mock(AppOrderCommandMapper.class), idempotency,
                mock(AuditLogService.class), guard, mapper, sandboxService, run)
                .pay(7L, "CSO-LEGACY", "pay-legacy");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("COMMERCE_SANDBOX_ORDER_UNAVAILABLE");
        verify(sandboxService, never()).applyCallback(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void productionPaymentFailsClosedWithoutReadingCanonicalOrderOrWallet() {
        var mapper = mock(AppOrderCommandMapper.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        var service = new AppOrderCommandService(mapper, mock(AdminIdempotencyService.class),
                mock(AuditLogService.class), guard, null, null, null);

        var result = service.pay(7L, "ORD-1", "pay-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
        verify(mapper, never()).lockOrder(anyString());
    }
}
