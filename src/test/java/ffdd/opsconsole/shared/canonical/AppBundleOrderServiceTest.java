package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.nullable;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class AppBundleOrderServiceTest {
    private final StorefrontProductReleasePolicy releasePolicy = mock(StorefrontProductReleasePolicy.class);

    @BeforeEach
    void releaseDefaultsOpen() {
        lenient().when(releasePolicy.evaluate(anyString(), nullable(String.class)))
                .thenReturn(StorefrontProductReleasePolicy.Decision.open(null));
    }

    @Test
    void pricesAndPersistsOneServerAuthoritativeBundle() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
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
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), "bundle-key-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderType", "BUNDLE")
                .containsEntry("subtotalUsdt", new BigDecimal("300.000000"))
                .containsEntry("discountUsdt", new BigDecimal("15.000000"))
                .containsEntry("amountUsdt", new BigDecimal("285.000000"));
        verify(mapper, times(2)).insertBundleItem(anyString(), any(), anyInt());
    }

    @Test
    void strictSandboxRequiresARealSandboxUserBeforeCheckout() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        var result = new AppBundleOrderService(mapper, mock(AdminIdempotencyService.class),
                mock(EventOutboxService.class), guard, new StorefrontPurchaseGatePolicy(),
                releasePolicy,
                mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), "bundle-key-1");
        assertThat(result.getMessage()).isEqualTo("USER_NOT_FOUND");
        verify(mapper).lockUser(7L);
    }

    @Test
    void nonProductionRuntimeWithoutSandboxRailRejectsBundleBeforeCanonicalReadsOrWrites() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(false);
        when(guard.isStrictProductionRuntime()).thenReturn(false);

        var result = new AppBundleOrderService(mapper, idempotency, outbox, guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy,
                mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), "mixed-bundle-key");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("COMMERCE_SANDBOX_UNAVAILABLE");
        verifyNoInteractions(mapper, idempotency, outbox);
    }

    @Test
    void sandboxBundleUsesOnlyRunScopedStateMachineOrders() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, true));
        CommerceAcceptanceSandboxMapper sandbox = mock(CommerceAcceptanceSandboxMapper.class);
        CommerceAcceptanceRun run = mock(CommerceAcceptanceRun.class);
        when(run.requireRunId()).thenReturn("test-run-0001");
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "s1", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(1L, "s1", "S1", "Entry",
                        new BigDecimal("100"), 2, 0, null, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        "", null, null, 0L, null));
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "pro", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(2L, "pro", "Pro", "Entry",
                        new BigDecimal("200"), 2, 0, null, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        "", null, null, 0L, null));
        when(sandbox.reserveSandboxCatalogStock(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(sandbox.insertSandboxOrder(any())).thenReturn(1);
        when(sandbox.insertInventory(any())).thenReturn(1);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run)
                .create(7L, List.of("s1", "pro"), "sandbox-bundle-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("orderType", "BUNDLE")
                .containsEntry("itemCount", 2);
        verify(mapper, never()).lockProducts(any());
        verify(sandbox).insertSandboxOrder(any());
        verify(sandbox, times(2)).insertInventory(any());
    }

    @Test
    void bundleAppliesServerPurchaseGateToEveryLine() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100"), 2,
                        "{\"rankMin\":3,\"mode\":\"all\",\"enforce\":true}"),
                new AppBundleOrderMapper.ProductRow(2L, "pro", "Pro", new BigDecimal("200"), 2, null)));
        when(mapper.purchaseFacts(7L)).thenReturn(new AppBundleOrderMapper.PurchaseFacts(2, 0, BigDecimal.ZERO));
        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), "bundle-gate-key");
        assertThat(result.getMessage()).isEqualTo("PURCHASE_GATE_BLOCKED");
        verify(mapper, never()).decrementStock(anyLong());
    }

    @Test
    void productionBundleConsumesEachCanonicalLifetimeQuotaBeforeStockWrites() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100"), 2),
                new AppBundleOrderMapper.ProductRow(2L, "pro", "Pro", new BigDecimal("200"), 2)));
        when(mapper.purchaseGateJson("s1"))
                .thenReturn("{\"mode\":\"all\",\"enforce\":true,\"quotaCap\":1,\"quotaSold\":0}");
        when(mapper.purchaseFacts(7L)).thenReturn(new AppBundleOrderMapper.PurchaseFacts(0, 0, BigDecimal.ZERO));
        when(mapper.consumePurchaseQuota("s1", 1)).thenReturn(1);
        when(mapper.deviceSlotCap()).thenReturn(6);
        when(mapper.attribution(7L)).thenReturn(new AppBundleOrderMapper.Attribution("P1", 1, "2026-W33"));
        when(mapper.decrementStock(anyLong())).thenReturn(1);
        when(mapper.insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), any(), any(), any())).thenReturn(1);
        when(mapper.insertBundleItem(anyString(), any(), anyInt())).thenReturn(1);

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), "bundle-quota-key");

        assertThat(result.getCode()).isZero();
        verify(mapper).consumePurchaseQuota("s1", 1);
        verify(mapper, times(2)).insertBundleItem(anyString(), any(), anyInt());
    }

    @Test
    void productionBundleCannotBypassServerReleaseGate() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100"), 2, null, "phase-2"),
                new AppBundleOrderMapper.ProductRow(2L, "pro", "Pro", new BigDecimal("200"), 2, null, "phase-2")));
        when(releasePolicy.evaluate(anyString(), anyString()))
                .thenReturn(StorefrontProductReleasePolicy.Decision.closed("E1_PHASE_NOT_REACHED", "phase-2"));

        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class),
                guard, new StorefrontPurchaseGatePolicy(), releasePolicy,
                mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), "bundle-release-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("BUNDLE_PRODUCT_NOT_RELEASED");
        verify(mapper, never()).decrementStock(anyLong());
    }

    @Test
    void sandboxBundleCannotBypassServerReleaseGate() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, true));
        CommerceAcceptanceSandboxMapper sandbox = mock(CommerceAcceptanceSandboxMapper.class);
        CommerceAcceptanceRun run = mock(CommerceAcceptanceRun.class);
        when(run.requireRunId()).thenReturn("test-run-0001");
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "aa", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(1L, "aa", "A", "Entry",
                        new BigDecimal("100"), 2, 0, null, null, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, "", null, "phase-2", 0L, null));
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "bb", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(2L, "bb", "B", "Entry",
                        new BigDecimal("200"), 2, 0, null, null, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, "", null, "phase-2", 0L, null));
        when(releasePolicy.evaluate(anyString(), anyString()))
                .thenReturn(StorefrontProductReleasePolicy.Decision.closed("E1_PHASE_NOT_REACHED", "phase-2"));
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run)
                .create(7L, List.of("bb", "aa"), "sandbox-release-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("BUNDLE_PRODUCT_NOT_RELEASED");
        verify(sandbox, never()).reserveSandboxCatalogStock(anyString(), anyLong(), anyLong(), anyInt());
        verify(sandbox, never()).insertSandboxOrder(any());
    }

    @Test
    void sandboxBundleLocksProductsInCanonicalOrderButKeepsResponseOrder() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, true));
        CommerceAcceptanceSandboxMapper sandbox = mock(CommerceAcceptanceSandboxMapper.class);
        CommerceAcceptanceRun run = mock(CommerceAcceptanceRun.class);
        when(run.requireRunId()).thenReturn("test-run-0001");
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "aa", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(1L, "aa", "A", "Entry", new BigDecimal("100"), 2, 0,
                        null, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "", null, null, 0L, null));
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "bb", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(2L, "bb", "B", "Entry", new BigDecimal("200"), 2, 0,
                        null, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "", null, null, 0L, null));
        when(sandbox.reserveSandboxCatalogStock(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(sandbox.insertSandboxOrder(any())).thenReturn(1);
        when(sandbox.insertInventory(any())).thenReturn(1);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run)
                .create(7L, List.of("bb", "aa"), "sandbox-order-lock-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("productNos", List.of("bb", "aa"));
        var calls = inOrder(sandbox);
        calls.verify(sandbox).lockSandboxCatalogProduct("test-run-0001", null, "aa", 1);
        calls.verify(sandbox).lockSandboxCatalogProduct("test-run-0001", null, "bb", 1);
    }

    @Test
    void sandboxBundleIdempotencyIsolatedByRunAndCannotReplayAcrossRuns() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, true));
        CommerceAcceptanceSandboxMapper sandbox = mock(CommerceAcceptanceSandboxMapper.class);
        CommerceAcceptanceRun run = mock(CommerceAcceptanceRun.class);
        when(run.requireRunId()).thenReturn("test-run-0001", "test-run-0002");
        when(sandbox.lockSandboxCatalogProduct(anyString(), nullable(Long.class), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String productNo = invocation.getArgument(2);
                    long productId = "aa".equals(productNo) ? 1L : 2L;
                    BigDecimal price = "aa".equals(productNo) ? new BigDecimal("100") : new BigDecimal("200");
                    return new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(productId, productNo,
                            productNo.toUpperCase(), "Entry", price, 2, 0, null, null, BigDecimal.ONE,
                            BigDecimal.ONE, BigDecimal.ONE, "", null, null, 0L, null);
                });
        when(sandbox.reserveSandboxCatalogStock(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(sandbox.insertSandboxOrder(any())).thenReturn(1);
        when(sandbox.insertInventory(any())).thenReturn(1);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        var scopes = new java.util.ArrayList<String>();
        var hashes = new java.util.ArrayList<String>();
        doAnswer(invocation -> {
            scopes.add(invocation.getArgument(0));
            hashes.add(invocation.getArgument(2));
            return ((Supplier<?>) invocation.getArgument(4)).get();
        }).when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var service = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run);
        var first = service.create(7L, List.of("bb", "aa"), "same-bundle-key");
        var second = service.create(7L, List.of("bb", "aa"), "same-bundle-key");

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isZero();
        assertThat(first.getData()).containsEntry("runId", "test-run-0001");
        assertThat(second.getData()).containsEntry("runId", "test-run-0002");
        assertThat(scopes).containsExactly(
                "APP:BUNDLE_ORDER_CREATE:SANDBOX:test-run-0001:USER:7",
                "APP:BUNDLE_ORDER_CREATE:SANDBOX:test-run-0002:USER:7");
        assertThat(hashes).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void sandboxBundleFailsClosedBeforeIdempotencyWhenRunIsMissing() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, true));
        CommerceAcceptanceSandboxMapper sandbox = mock(CommerceAcceptanceSandboxMapper.class);
        CommerceAcceptanceRun run = mock(CommerceAcceptanceRun.class);
        when(run.requireRunId()).thenThrow(new ffdd.opsconsole.shared.exception.BizException(
                503, "COMMERCE_SANDBOX_RUN_ID_REQUIRED"));
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);

        var service = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run);

        assertThatThrownBy(() -> service.create(7L, List.of("aa", "bb"), "missing-run-key"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessageContaining("COMMERCE_SANDBOX_RUN_ID_REQUIRED");
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }
}
