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
import ffdd.opsconsole.shared.exception.BizException;
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
    void monthlyQuotaExhaustionNeverDebitsWalletOrCreatesDevices() {
        var mapper = mock(AppOrderCommandMapper.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var idempotency = mock(AdminIdempotencyService.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-QUOTA")).thenReturn(new AppOrderCommandMapper.DevelopmentPayOrder(
                "ORD-QUOTA", 7L, 18L, 2, new BigDecimal("100"), null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockOrderMonthlyQuotas("ORD-QUOTA")).thenReturn(List.of(
                new AppOrderCommandMapper.MonthlyQuota(1L, "PRO", "stellarbox-pro", 10, 1, 2)));
        when(mapper.lockMonthlyQuotaUsage(eq(1L), any(), any())).thenReturn(List.of(9));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-QUOTA", "quota-test-key");
        assertThat(result.getMessage()).isEqualTo("ORDER_MONTHLY_QUOTA_EXHAUSTED");
        verify(mapper, never()).debitDevelopmentWallet(any(), any(), any());
        verify(mapper, never()).consumeMonthlyQuota(any(), any(), any(), any());
        verify(mapper, never()).insertDevelopmentDevice(any(), any(), any(), any());
    }

    @Test
    void expiryReturnsStockQuotaAndVoucherBeforeMarkingTheOrderExpired() {
        var mapper = mock(AppOrderCommandMapper.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        var audit = mock(AuditLogService.class);
        when(mapper.lockOrder("ORD-EXPIRED-1")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "ORD-EXPIRED-1", 7L, 1L, 1, "SINGLE", 1,
                "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockItems("ORD-EXPIRED-1")).thenReturn(List.of(
                new AppOrderCommandMapper.ItemRow("ORD-EXPIRED-1", 1L, "S1", 1, true, 7L)));
        when(mapper.lockProduct(1L)).thenReturn(new AppOrderCommandMapper.ProductRow(1L, 0, 1));
        when(mapper.returnStock(1L, 1)).thenReturn(1);
        when(mapper.lockLifetimeQuotaState("S1"))
                .thenReturn(new AppOrderCommandMapper.QuotaState(1, 7L));
        when(mapper.releaseLifetimeQuota("S1", 1, 7L)).thenReturn(1);
        when(mapper.lockUsedVouchersForOrder(7L, "ORD-EXPIRED-1")).thenReturn(List.of(
                new AppOrderCommandMapper.VoucherGrantRow("VGR-19")));
        when(mapper.expireOrder("ORD-EXPIRED-1", 7L)).thenReturn(1);
        when(mapper.restoreVoucher("VGR-19", 7L, "ORD-EXPIRED-1")).thenReturn(1);

        var service = new AppOrderCommandService(mapper, mock(AdminIdempotencyService.class), audit,
                guard, null, null, null);

        assertThat(service.expirePendingOrder(7L, "ORD-EXPIRED-1")).isTrue();
        verify(mapper).returnStock(1L, 1);
        verify(mapper).releaseLifetimeQuota("S1", 1, 7L);
        verify(mapper).restoreVoucher("VGR-19", 7L, "ORD-EXPIRED-1");
        verify(mapper).expireOrder("ORD-EXPIRED-1", 7L);
        verify(audit).recordRequired(argThat(row -> "APP_ORDER_EXPIRED".equals(row.getAction())));
    }

    @Test
    void fullyDiscountedOrderActivatesWithoutDebitingOrWritingAnOutflowLedger() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var audit = mock(AuditLogService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-FREE-1")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-FREE-1", 7L, 18L, 1, new BigDecimal("0.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        var quota = new AppOrderCommandMapper.MonthlyQuota(1L, "PRO", "stellarbox-pro", 10, 1, 1);
        when(mapper.lockOrderMonthlyQuotas("ORD-FREE-1")).thenReturn(List.of(quota));
        when(mapper.lockMonthlyQuotaUsage(eq(1L), any(), any())).thenReturn(List.of(9));
        when(mapper.consumeMonthlyQuota(eq(quota), eq(7L), eq("ORD-FREE-1"), any())).thenReturn(1);
        when(mapper.lockUsedVouchersForOrder(7L, "ORD-FREE-1")).thenReturn(List.of(
                new AppOrderCommandMapper.VoucherGrantRow("VGR-FREE-1")));
        when(mapper.markDevelopmentOrderActivated(eq("ORD-FREE-1"), eq(7L), startsWith("PAY-VOUCHER-")))
                .thenReturn(1);
        when(mapper.insertDevelopmentPayment(eq("ORD-FREE-1"), eq(7L), startsWith("PAY-VOUCHER-"),
                eq(new BigDecimal("0.000000")))).thenReturn(1);
        when(mapper.insertDevelopmentDevice(eq("ORD-FREE-1"), eq(7L), startsWith("NEX-ORD-"), eq(0)))
                .thenReturn(1);
        when(mapper.insertDevelopmentOrderHistory(eq("ORD-FREE-1"), eq("placed"), eq("activated"),
                anyString())).thenReturn(1);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, audit, guard, null, null, null)
                .pay(7L, "ORD-FREE-1", "fully-discounted-payment");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("amountUsdt", new BigDecimal("0.000000"))
                .containsEntry("walletBalanceAfterUsdt", null)
                .containsEntry("paymentMethod", "VOUCHER")
                .containsEntry("canonicalStatus", "activated");
        verify(mapper, never()).lockDevelopmentWallet(anyLong());
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
        verify(mapper, never()).insertDevelopmentPurchaseLedger(anyString(), anyLong(), any(), any());
        verify(mapper).markDevelopmentOrderActivated(eq("ORD-FREE-1"), eq(7L), startsWith("PAY-VOUCHER-"));
        verify(mapper).consumeMonthlyQuota(eq(quota), eq(7L), eq("ORD-FREE-1"), any());
        verify(mapper).insertDevelopmentPayment(eq("ORD-FREE-1"), eq(7L), startsWith("PAY-VOUCHER-"),
                eq(new BigDecimal("0.000000")));
        verify(audit).recordRequired(any());
    }

    @Test
    void zeroAmountOrderWithoutExactlyOneConsumedVoucherIsRejectedBeforeSettlement() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-FREE-NO-VOUCHER")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-FREE-NO-VOUCHER", 7L, 18L, 1, new BigDecimal("0.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockUsedVouchersForOrder(7L, "ORD-FREE-NO-VOUCHER")).thenReturn(List.of());
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-FREE-NO-VOUCHER", "free-without-voucher");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ORDER_VOUCHER_SETTLEMENT_INVALID");
        verify(mapper, never()).lockDevelopmentWallet(anyLong());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
    }

    @Test
    void pendingPaymentIsRejectedWithoutDebitWhenE1HasUnlistedTheCurrentProduct() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-E1-UNLISTED")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-E1-UNLISTED", 7L, 18L, 1, new BigDecimal("199.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("500.000000"), 2L));
        when(mapper.hasNonPayableOrderProduct("ORD-E1-UNLISTED")).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-E1-UNLISTED", "payment-e1-unlisted");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ORDER_PRODUCT_NOT_PAYABLE");
        var productLockOrder = org.mockito.Mockito.inOrder(mapper);
        productLockOrder.verify(mapper).lockOrderProductsForPayment("ORD-E1-UNLISTED");
        productLockOrder.verify(mapper).lockOrderSkusForPayment("ORD-E1-UNLISTED");
        productLockOrder.verify(mapper).hasNonPayableOrderProduct("ORD-E1-UNLISTED");
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
    }

    @Test
    void expiredPendingPaymentIsRejectedBeforeAnyWalletDebit() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-EXPIRED-PAY")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-EXPIRED-PAY", 7L, 18L, 1, new BigDecimal("199.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("500.000000"), 2L));
        when(mapper.countExpiredPayableOrder("ORD-EXPIRED-PAY", 7L, 30)).thenReturn(1);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-EXPIRED-PAY", "payment-expired");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ORDER_PAYMENT_EXPIRED");
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
    }

    @Test
    void pendingPaymentIsRejectedWithoutDebitWhenGlobalMaintenanceStopIsActive() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-EMERGENCY-STOP")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-EMERGENCY-STOP", 7L, 18L, 1, new BigDecimal("199.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("500.000000"), 2L));
        when(mapper.emergencyValue("killswitch.maintenance")).thenReturn("enabled");
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-EMERGENCY-STOP", "payment-emergency-stop");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("COMMERCE_PAYMENT_EMERGENCY_STOPPED");
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
    }

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
        when(mapper.markDevelopmentOrderActivated(eq("ORD-DEV-1"), eq(7L), startsWith("PAY-WALLET-")))
                .thenReturn(1);
        when(mapper.insertDevelopmentPayment(eq("ORD-DEV-1"), eq(7L), startsWith("PAY-WALLET-"),
                eq(new BigDecimal("1199.000000")))).thenReturn(1);
        when(mapper.insertDevelopmentDevice(eq("ORD-DEV-1"), eq(7L), startsWith("NEX-ORD-"), eq(0)))
                .thenReturn(1);
        when(mapper.developmentDeviceFact(startsWith("NEX-ORD-"))).thenReturn(
                new AppOrderCommandMapper.DevelopmentDeviceFact(91L, "NEX-ORD-INSTANCE"));
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
        verify(mapper).insertDevelopmentDevice(eq("ORD-DEV-1"), eq(7L), startsWith("NEX-ORD-"), eq(0));
        verify(outbox).publishUserEvent(eq("DEVICE"), eq("91"), eq("admin.device_activated"), eq(7L),
                eq("P3"), eq(0), eq("2026-W34"), argThat(payload -> payload instanceof Map<?, ?> map
                        && map.get("deviceId").equals(91L)
                        && map.get("instanceNo").equals("NEX-ORD-INSTANCE")
                        && map.get("mode").equals("NEXGRID_WALLET")));
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

        var service = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null);

        assertThatThrownBy(() -> service.pay(7L, "ORD-DEV-LOW", "development-pay-low"))
                .isInstanceOf(BizException.class)
                .hasMessage("ORDER_WALLET_INSUFFICIENT");
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
        verify(mapper, never()).insertDevelopmentPurchaseLedger(anyString(), anyLong(), any(), any());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
        verify(mapper, never()).insertDevelopmentPayment(anyString(), anyLong(), anyString(), any());
        verify(mapper, never()).insertDevelopmentDevice(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void walletCasLossDoesNotActivateOrRecordASecondConcurrentPayment() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-WALLET-CAS")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-WALLET-CAS", 7L, 18L, 1, new BigDecimal("20.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("30.000000"), 4L));
        when(mapper.debitDevelopmentWallet(7L, new BigDecimal("20.000000"), 4L)).thenReturn(0);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-WALLET-CAS", "wallet-cas-key"))
                .isInstanceOf(BizException.class)
                .hasMessage("ORDER_WALLET_CONFLICT");
        verify(mapper).debitDevelopmentWallet(7L, new BigDecimal("20.000000"), 4L);
        verify(mapper, never()).insertDevelopmentPurchaseLedger(anyString(), anyLong(), any(), any());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
        verify(mapper, never()).insertDevelopmentPayment(anyString(), anyLong(), anyString(), any());
        verify(mapper, never()).insertDevelopmentDevice(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void pendingOrderWithLegacyHdPaySessionIsQuarantinedBeforeWalletDebit() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-HDPAY-LEGACY")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-HDPAY-LEGACY", 7L, 18L, 1, new BigDecimal("199.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("500.000000"), 2L));
        when(mapper.countNonCancellableHdPaySessions("ORD-HDPAY-LEGACY")).thenReturn(1L);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null).pay(7L, "ORD-HDPAY-LEGACY", "wallet-pay-legacy");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("HDPAY_COMMERCE_PAYMENT_REVIEW_REQUIRED");
        verify(mapper, never()).debitDevelopmentWallet(anyLong(), any(), anyLong());
        verify(mapper, never()).insertDevelopmentPurchaseLedger(anyString(), anyLong(), any(), any());
        verify(mapper, never()).markDevelopmentOrderActivated(anyString(), anyLong(), anyString());
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
                        "PAY-WALLET-AF2D80199A1F88C85A3C8B75AEC54D43",
                        "PAID", "COMPLETED", "ACTIVATED"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("801.000000"), 5L));
        when(mapper.countNonCancellableHdPaySessions("ORD-DEV-PAID")).thenReturn(1L);
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
        verify(mapper, never()).countNonCancellableHdPaySessions("ORD-DEV-PAID");
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
                new AppOrderCommandMapper.ItemRow("BND-1", 1L, "S1", 1, true, 7L),
                new AppOrderCommandMapper.ItemRow("BND-1", 2L, "PRO", 1, false)));
        when(mapper.lockProduct(1L)).thenReturn(new AppOrderCommandMapper.ProductRow(1L, 0, 1));
        when(mapper.lockProduct(2L)).thenReturn(new AppOrderCommandMapper.ProductRow(2L, 0, 1));
        when(mapper.returnStock(1L, 1)).thenReturn(1);
        when(mapper.returnStock(2L, 1)).thenReturn(1);
        when(mapper.lockLifetimeQuotaState("S1"))
                .thenReturn(new AppOrderCommandMapper.QuotaState(1, 7L));
        when(mapper.releaseLifetimeQuota("S1", 1, 7L)).thenReturn(1);
        when(mapper.cancelOrder("BND-1", 7L)).thenReturn(1);

        var result = new AppOrderCommandService(mapper, idempotency, audit, guard, null, null, null)
                .cancel(7L, "BND-1", "cancel-key-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderNo", "BND-1").containsEntry("orderStatus", "CANCELLED")
                .containsEntry("serverCanonical", true).containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        verify(mapper).returnStock(1L, 1);
        verify(mapper).returnStock(2L, 1);
        verify(mapper).releaseLifetimeQuota("S1", 1, 7L);
        verify(mapper, never()).lockLifetimeQuotaState("PRO");
        verify(mapper, never()).releaseLifetimeQuota("PRO", 1, 0L);
        verify(audit).recordRequired(any());
    }

    @Test
    void cancellingAnUnpaidOrderRestoresItsConsumedVoucherInTheSameTransaction() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("ORD-VOUCHER")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "ORD-VOUCHER", 7L, 1L, 1, "SINGLE", 1,
                "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockItems("ORD-VOUCHER")).thenReturn(List.of(
                new AppOrderCommandMapper.ItemRow("ORD-VOUCHER", 1L, "S1", 1)));
        when(mapper.lockProduct(1L)).thenReturn(new AppOrderCommandMapper.ProductRow(1L, 0, 1));
        when(mapper.returnStock(1L, 1)).thenReturn(1);
        when(mapper.lockUsedVouchersForOrder(7L, "ORD-VOUCHER")).thenReturn(List.of(
                new AppOrderCommandMapper.VoucherGrantRow("VGR-19")));
        when(mapper.cancelOrder("ORD-VOUCHER", 7L)).thenReturn(1);
        when(mapper.restoreVoucher("VGR-19", 7L, "ORD-VOUCHER")).thenReturn(1);

        var result = new AppOrderCommandService(
                mapper, idempotency, mock(AuditLogService.class), guard, null, null, null)
                .cancel(7L, "ORD-VOUCHER", "cancel-key-voucher");

        assertThat(result.getCode()).isZero();
        verify(mapper).restoreVoucher("VGR-19", 7L, "ORD-VOUCHER");
    }

    @Test
    void activeCommercePaymentIntentMakesOrderNonCancellableBeforeProviderRowExists() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("ORD-HDPAY-PREPARING")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "ORD-HDPAY-PREPARING", 7L, 1L, 1, "SINGLE", 1,
                "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.countNonCancellableHdPaySessions("ORD-HDPAY-PREPARING")).thenReturn(1L);

        var result = new AppOrderCommandService(
                mapper, idempotency, mock(AuditLogService.class), guard, null, null, null)
                .cancel(7L, "ORD-HDPAY-PREPARING", "cancel-key-hdpay-preparing");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("HDPAY_ORDER_NOT_CANCELLABLE");
        verify(mapper, never()).lockItems(anyString());
        verify(mapper, never()).returnStock(anyLong(), anyInt());
        verify(mapper, never()).cancelOrder(anyString(), anyLong());
    }

    @Test
    void cancelsReservedOrderAfterOperatorResetsQuotaWithoutDecrementingTheNewQuota() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("ORD-RESET")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "ORD-RESET", 7L, 1L, 1, "SINGLE", 1, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockItems("ORD-RESET")).thenReturn(List.of(
                new AppOrderCommandMapper.ItemRow("ORD-RESET", 1L, "S1", 1, true, 7L)));
        when(mapper.lockProduct(1L)).thenReturn(new AppOrderCommandMapper.ProductRow(1L, 0, 1));
        when(mapper.returnStock(1L, 1)).thenReturn(1);
        when(mapper.lockLifetimeQuotaState("S1"))
                .thenReturn(new AppOrderCommandMapper.QuotaState(3, 8L));
        when(mapper.cancelOrder("ORD-RESET", 7L)).thenReturn(1);

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard, null, null, null)
                .cancel(7L, "ORD-RESET", "cancel-key-reset");

        assertThat(result.getCode()).isZero();
        verify(mapper).returnStock(1L, 1);
        verify(mapper, never()).releaseLifetimeQuota(anyString(), anyInt(), anyLong());
        verify(mapper).cancelOrder("ORD-RESET", 7L);
    }

    @Test
    void cancelsReservedOrderAfterOperatorDeletesQuotaConfigurationWithoutRollback() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockOrder("ORD-GATE-DELETED")).thenReturn(new AppOrderCommandMapper.OrderRow(
                "ORD-GATE-DELETED", 7L, 1L, 1, "SINGLE", 1, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockItems("ORD-GATE-DELETED")).thenReturn(List.of(
                new AppOrderCommandMapper.ItemRow("ORD-GATE-DELETED", 1L, "S1", 1, true, 7L)));
        when(mapper.lockProduct(1L)).thenReturn(new AppOrderCommandMapper.ProductRow(1L, 0, 1));
        when(mapper.returnStock(1L, 1)).thenReturn(1);
        when(mapper.lockLifetimeQuotaState("S1")).thenReturn(null);
        when(mapper.cancelOrder("ORD-GATE-DELETED", 7L)).thenReturn(1);

        var result = new AppOrderCommandService(mapper, idempotency, mock(AuditLogService.class), guard, null, null, null)
                .cancel(7L, "ORD-GATE-DELETED", "cancel-key-gate-deleted");

        assertThat(result.getCode()).isZero();
        verify(mapper).returnStock(1L, 1);
        verify(mapper, never()).releaseLifetimeQuota(anyString(), anyInt(), anyLong());
        verify(mapper).cancelOrder("ORD-GATE-DELETED", 7L);
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
    void productionPaymentDebitsTheNexGridWalletAndActivatesTheOrder() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var audit = mock(AuditLogService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("ORD-1")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "ORD-1", 7L, 18L, 1, new BigDecimal("10.000000"),
                        null, "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("25.000000"), 2L));
        when(mapper.debitDevelopmentWallet(7L, new BigDecimal("10.000000"), 2L)).thenReturn(1);
        when(mapper.insertDevelopmentPurchaseLedger(
                "ORD-1", 7L, new BigDecimal("10.000000"), new BigDecimal("15.000000"))).thenReturn(1);
        when(mapper.markDevelopmentOrderActivated(eq("ORD-1"), eq(7L), startsWith("PAY-WALLET-")))
                .thenReturn(1);
        when(mapper.insertDevelopmentPayment(eq("ORD-1"), eq(7L), startsWith("PAY-WALLET-"),
                eq(new BigDecimal("10.000000")))).thenReturn(1);
        when(mapper.insertDevelopmentDevice(eq("ORD-1"), eq(7L), startsWith("NEX-ORD-"), eq(0)))
                .thenReturn(1);
        when(mapper.developmentDeviceFact(startsWith("NEX-ORD-"))).thenReturn(
                new AppOrderCommandMapper.DevelopmentDeviceFact(92L, "NEX-ORD-PROD"));
        when(mapper.attribution(7L)).thenReturn(Map.of(
                "phase", "P3", "accountAgeMonths", 1, "cohort", "2026-W35"));
        when(mapper.insertDevelopmentOrderHistory(eq("ORD-1"), eq("placed"), eq("activated"),
                anyString())).thenReturn(1);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        var outbox = mock(EventOutboxService.class);
        var service = new AppOrderCommandService(
                mapper, idempotency, audit, guard, null, null, null, outbox);

        var result = service.pay(7L, "ORD-1", "pay-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderNo", "ORD-1")
                .containsEntry("paymentStatus", "PAID")
                .containsEntry("canonicalStatus", "activated")
                .containsEntry("walletBalanceAfterUsdt", new BigDecimal("15.000000"));
        verify(mapper).debitDevelopmentWallet(7L, new BigDecimal("10.000000"), 2L);
        verify(mapper).insertDevelopmentPurchaseLedger(
                "ORD-1", 7L, new BigDecimal("10.000000"), new BigDecimal("15.000000"));
        verify(audit).recordRequired(any());
    }

    @Test
    void bundleWalletPaymentActivatesEachCanonicalOrderItemExactlyOnce() {
        var mapper = mock(AppOrderCommandMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.activeUserEnvironment(7L)).thenReturn(0);
        when(mapper.lockDevelopmentPayOrder("BND-WALLET-1")).thenReturn(
                new AppOrderCommandMapper.DevelopmentPayOrder(
                        "BND-WALLET-1", 7L, 18L, 2, "BUNDLE", 2,
                        new BigDecimal("30.000000"), null,
                        "PENDING", "PENDING_PAYMENT", "WAITING_PAYMENT"));
        when(mapper.lockDevelopmentPaymentItems("BND-WALLET-1")).thenReturn(List.of(
                new AppOrderCommandMapper.DevelopmentPaymentItem(18L, "SKU-A", 1, 0),
                new AppOrderCommandMapper.DevelopmentPaymentItem(19L, "SKU-B", 1, 1)));
        when(mapper.lockDevelopmentWallet(7L)).thenReturn(
                new AppOrderCommandMapper.DevelopmentWallet(new BigDecimal("50.000000"), 4L));
        when(mapper.debitDevelopmentWallet(7L, new BigDecimal("30.000000"), 4L)).thenReturn(1);
        when(mapper.insertDevelopmentPurchaseLedger(
                "BND-WALLET-1", 7L, new BigDecimal("30.000000"), new BigDecimal("20.000000")))
                .thenReturn(1);
        when(mapper.markDevelopmentOrderActivated(eq("BND-WALLET-1"), eq(7L), anyString()))
                .thenReturn(1);
        when(mapper.insertDevelopmentPayment(eq("BND-WALLET-1"), eq(7L), anyString(),
                eq(new BigDecimal("30.000000")))).thenReturn(1);
        when(mapper.insertWalletDevice(eq("BND-WALLET-1"), eq(7L), anyLong(), anyString(), eq(0)))
                .thenReturn(1);
        when(mapper.developmentDeviceFact(anyString())).thenAnswer(invocation ->
                new AppOrderCommandMapper.DevelopmentDeviceFact(
                        invocation.getArgument(0, String.class).hashCode() & 0x7fffffffL,
                        invocation.getArgument(0, String.class)));
        when(mapper.attribution(7L)).thenReturn(Map.of(
                "phase", "P3", "accountAgeMonths", 1, "cohort", "2026-W35"));
        when(mapper.insertDevelopmentOrderHistory(
                eq("BND-WALLET-1"), eq("placed"), eq("activated"), anyString())).thenReturn(1);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppOrderCommandService(
                mapper, idempotency, mock(AuditLogService.class), guard,
                null, null, null, mock(EventOutboxService.class))
                .pay(7L, "BND-WALLET-1", "bundle-wallet-pay-key");

        assertThat(result.getCode()).isZero();
        verify(mapper).insertWalletDevice(
                eq("BND-WALLET-1"), eq(7L), eq(18L), startsWith("NEX-ORD-"), eq(0));
        verify(mapper).insertWalletDevice(
                eq("BND-WALLET-1"), eq(7L), eq(19L), startsWith("NEX-ORD-"), eq(0));
        verify(mapper, times(2)).insertWalletDevice(
                eq("BND-WALLET-1"), eq(7L), anyLong(), anyString(), anyInt());
    }
}
