package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import ffdd.opsconsole.shared.api.ApiResult;
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
    void persistsDistinctLinesAndTotalUnitsWithQuantityAwarePriceStockAndCapacity() {
        var mapper = mock(AppBundleOrderMapper.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).executeRetained(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100"), 2),
                new AppBundleOrderMapper.ProductRow(2L, "pro", "Pro", new BigDecimal("200"), 3)));
        when(mapper.deviceSlotCap()).thenReturn(5);
        when(mapper.attribution(7L)).thenReturn(new AppBundleOrderMapper.Attribution("P1", 1, "2026-W41"));
        when(mapper.decrementStock(anyLong(), anyInt())).thenReturn(1);
        when(mapper.insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);
        when(mapper.insertBundleItem(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(1);
        when(mapper.snapshotBundleComputePoints(anyString())).thenReturn(1);
        var service = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, null, null);
        var items = List.of(new AppBundleOrderService.BundleItem("s1", 2), new AppBundleOrderService.BundleItem("pro", 3));

        var result = service.create(7L, null, items, 1L, new BigDecimal("760"), null, "bundle-quantities");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("itemCount", 2).containsEntry("quantity", 5)
                .containsEntry("subtotalUsdt", new BigDecimal("800.000000"))
                .containsEntry("amountUsdt", new BigDecimal("760.000000"));
        verify(mapper).decrementStock(1L, 2);
        verify(mapper).decrementStock(2L, 3);
        verify(mapper).insertBundleOrder(eq(7L), anyString(), eq(2L), eq(2), eq(5),
                eq(new BigDecimal("800.000000")), eq(new BigDecimal("40.000000")), eq(new BigDecimal("760.000000")));
        verify(mapper).insertBundleItem(anyString(), argThat(row -> "s1".equals(row.productNo())), eq(2), eq(1), eq(false), eq(null));
        when(mapper.deviceSlotCap()).thenReturn(4);
        assertThat(service.create(7L, null, items, 1L, new BigDecimal("760"), null, "bundle-capacity").getMessage())
                .isEqualTo("CAPACITY_REPLACEMENT_REQUIRED");
        verify(mapper, times(2)).decrementStock(anyLong(), anyInt());
    }

    @Test
    void rejectsAmbiguousDuplicateAndOversizedItemInputsBeforeLocking() {
        var mapper = mock(AppBundleOrderMapper.class);
        var service = new AppBundleOrderService(mapper, mock(AdminIdempotencyService.class), mock(EventOutboxService.class),
                mock(FundsSandboxProfileGuard.class), new StorefrontPurchaseGatePolicy(), releasePolicy, null, null);
        var valid = List.of(new AppBundleOrderService.BundleItem("s1", 1), new AppBundleOrderService.BundleItem("pro", 1));
        assertThat(service.create(7L, List.of("s1", "pro"), valid, 1L, BigDecimal.ONE, null, "k").getCode()).isEqualTo(422);
        for (var invalid : List.of(
                List.of(new AppBundleOrderService.BundleItem("s1", 1), new AppBundleOrderService.BundleItem("s1", 2)),
                List.of(new AppBundleOrderService.BundleItem("s1", 0), new AppBundleOrderService.BundleItem("pro", 1)),
                List.of(new AppBundleOrderService.BundleItem("s1", 100), new AppBundleOrderService.BundleItem("pro", 1)))) {
            assertThat(service.create(7L, null, invalid, 1L, BigDecimal.ONE, null, "k").getCode()).isEqualTo(422);
        }
        verifyNoInteractions(mapper);
    }
    @Test void promotedBundleUsesValidatedDiscountAndReservesEveryPurchasedUnitOnce(){
        var mapper=mock(AppBundleOrderMapper.class);var idem=mock(AdminIdempotencyService.class);var guard=mock(FundsSandboxProfileGuard.class);
        var promotions=mock(ffdd.opsconsole.promotion.application.PromotionOrderService.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(i->((Supplier<?>)i.getArgument(4)).get()).when(idem).executeRetained(anyString(),anyString(),anyString(),any(),any());
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L,false));
        when(mapper.lockProducts(any())).thenReturn(List.of(new AppBundleOrderMapper.ProductRow(1L,"s1","S1",new BigDecimal("100"),2),new AppBundleOrderMapper.ProductRow(2L,"pro","Pro",new BigDecimal("200"),3)));
        when(mapper.deviceSlotCap()).thenReturn(5);when(mapper.attribution(7L)).thenReturn(new AppBundleOrderMapper.Attribution("P1",1,"2026-W41"));
        when(mapper.decrementStock(anyLong(),anyInt())).thenReturn(1);when(mapper.insertBundleOrder(anyLong(),anyString(),anyLong(),anyInt(),anyInt(),any(),any(),any())).thenReturn(1);
        when(mapper.insertBundleItem(anyString(),any(),anyInt(),anyInt(),anyBoolean(),any())).thenReturn(1);
        when(mapper.snapshotBundleComputePoints(anyString())).thenReturn(1);
        when(mapper.orderDeadlineEpoch(anyString(),anyInt())).thenReturn(java.time.Instant.now().plusSeconds(1800).getEpochSecond());
        var plan=new ffdd.opsconsole.promotion.application.PromotionOrderService.CreationPlan("Q1",7L,List.of(7L),List.of("s1","pro"),java.time.Instant.now().plusSeconds(600),
                java.util.Map.of("public",java.util.Map.of("subtotalUsdt","800.000000","discountUsdt","140.000000","amountUsdt","660.000000")));
        when(promotions.prepareCreate(eq(7L),eq("Q1"),any(),eq(null),eq(new BigDecimal("660")))).thenReturn(plan);
        when(promotions.reserveCreatedOrder(eq(plan),anyString(),any())).thenReturn(java.util.Map.of("promotionDiscountUsdt","100.000000"));
        var service=new AppBundleOrderService(mapper,idem,mock(EventOutboxService.class),guard,new StorefrontPurchaseGatePolicy(),releasePolicy,null,null,null,promotions,30);
        var result=service.create(7L,null,List.of(new AppBundleOrderService.BundleItem("s1",2),new AppBundleOrderService.BundleItem("pro",3)),1L,new BigDecimal("660"),"Q1","promoted-bundle");
        assertThat(result.getCode()).isZero();assertThat(result.getData()).containsEntry("amountUsdt",new BigDecimal("660.000000"));
        verify(mapper).insertBundleOrder(eq(7L),anyString(),eq(2L),eq(2),eq(5),eq(new BigDecimal("800.000000")),eq(new BigDecimal("140.000000")),eq(new BigDecimal("660.000000")));
        verify(mapper).decrementStock(1L,2);verify(mapper).decrementStock(2L,3);
    }

    @Test
    @SuppressWarnings("unchecked")
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
        when(mapper.decrementStock(anyLong(), anyInt())).thenReturn(1);
        when(mapper.insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);
        when(mapper.insertBundleItem(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(1);
        when(mapper.snapshotBundleComputePoints(anyString())).thenReturn(1);

        var result = new AppBundleOrderService(mapper, idempotency, outbox, guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), 1L, new BigDecimal("285"), "bundle-key-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderType", "BUNDLE")
                .containsEntry("subtotalUsdt", new BigDecimal("300.000000"))
                .containsEntry("discountUsdt", new BigDecimal("15.000000"))
                .containsEntry("amountUsdt", new BigDecimal("285.000000"));
        verify(mapper, times(2)).insertBundleItem(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any());
        String orderNo = (String) result.getData().get("orderNo");
        var payload = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(outbox).publishUserEvent(eq("ORDER"), eq(orderNo), eq("checkout.started"),
                eq(7L), eq("P1"), eq(1), eq("2026-W33"), payload.capture());
        verify(mapper).insertBundleOrder(eq(7L), eq(orderNo), eq(2L), eq(2), eq(2),
                eq(new BigDecimal("300.000000")), eq(new BigDecimal("15.000000")), eq(new BigDecimal("285.000000")));
        assertThat(result.getData()).containsEntry("itemCount", 2).containsEntry("quantity", 2)
                .containsEntry("productNos", List.of("stellarbox-pro", "stellarbox-s1"));

        var schemaMapper = mock(ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class);
        when(schemaMapper.findActiveSchema("checkout.started"))
                .thenReturn(new ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.SchemaGateRow("conversion", 32, true));
        when(schemaMapper.listActiveProperties("checkout.started")).thenReturn(List.of(
                new ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.SchemaPropertyGateRow("user_id", "id", true),
                new ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.SchemaPropertyGateRow("order_id", "id", true),
                new ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.SchemaPropertyGateRow("product_id", "id", true),
                new ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.SchemaPropertyGateRow("quantity", "number", true),
                new ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.SchemaPropertyGateRow("amount_usdt", "number", true)));
        var registeredOutbox = new EventOutboxService(schemaMapper, new com.fasterxml.jackson.databind.ObjectMapper(),
                new ffdd.opsconsole.shared.outbox.OutboxProperties(),
                mock(ffdd.opsconsole.platform.application.A4RuntimePolicyService.class));
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(payload.getValue()).containsOnlyKeys("userId", "orderId", "productId", "quantity", "amountUsdt")
                        .containsEntry("userId", 7L).containsEntry("orderId", orderNo).containsEntry("productId", 2L)
                        .containsEntry("quantity", 2).containsEntry("amountUsdt", new BigDecimal("285.000000")),
                () -> {
                    assertThat(registeredOutbox.publishUserEvent("ORDER", orderNo, "checkout.started", 7L,
                            "P1", 1, "2026-W33", payload.getValue())).isNotBlank();
                    verify(schemaMapper).insertEvent(anyString(), eq("ORDER"), eq(orderNo), eq("checkout.started"),
                            eq("checkout.started"), eq("conversion"), eq("P1"), eq(1), eq("2026-W33"),
                            eq(true), eq(32), eq(true), eq(true), anyString());
                });
    }

    @Test
    void productionBundleKeepsAConfirmedSixDecimalAmount() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100.01"), 2),
                new AppBundleOrderMapper.ProductRow(2L, "pro", "Pro", new BigDecimal("100.01"), 2)));
        when(mapper.deviceSlotCap()).thenReturn(6);
        when(mapper.attribution(7L)).thenReturn(new AppBundleOrderMapper.Attribution("P1", 1, "2026-W33"));
        when(mapper.decrementStock(anyLong(), anyInt())).thenReturn(1);
        when(mapper.insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);
        when(mapper.insertBundleItem(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(1);
        when(mapper.snapshotBundleComputePoints(anyString())).thenReturn(1);

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), 1L, new BigDecimal("190.019"), "bundle-six-decimals");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("amountUsdt", new BigDecimal("190.019000"));
    }

    @Test
    void productionBundleRejectsAChangedConfirmedAmountBeforeQuotaStockOrOrderWrites() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100.50"), 2),
                new AppBundleOrderMapper.ProductRow(2L, "pro", "Pro", new BigDecimal("100.50"), 2)));
        when(mapper.deviceSlotCap()).thenReturn(6);
        when(mapper.attribution(7L)).thenReturn(new AppBundleOrderMapper.Attribution("P1", 1, "2026-W33"));

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), 1L, new BigDecimal("190.94"), "bundle-quote-stale");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("BUNDLE_QUOTE_STALE");
        verify(mapper, never()).consumePurchaseQuota(anyString(), anyInt());
        verify(mapper, never()).decrementStock(anyLong(), anyInt());
        verify(mapper, never()).insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void productionBundleRequiresAnExplicitConfirmedAmountFromEveryClient() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), 1L, null, "bundle-no-quote");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("BUNDLE_QUOTE_REQUIRED");
        verify(mapper, never()).lockProducts(any());
        verify(mapper, never()).decrementStock(anyLong(), anyInt());
    }

    @Test
    void aLegacyRetryCanRecoverItsExistingIdempotentBundleBeforeQuoteValidation() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(ApiResult.ok(java.util.Map.of("orderNo", "BND-LEGACY")));

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), 1L, null, "legacy-retry-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderNo", "BND-LEGACY");
        verify(mapper, never()).lockProducts(any());
        verify(mapper, never()).decrementStock(anyLong(), anyInt());
    }

    @Test
    void mixedCloudShareBundleCountsBothItemsAgainstTheSharedCapacity() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "stellarbox-s1", "S1", new BigDecimal("100"), 2,
                        null, null, "DEVICE", "FINITE", "configured", 1, "500W", "DC-HCM"),
                new AppBundleOrderMapper.ProductRow(2L, "cloud-share", "Share", new BigDecimal("200"), 0,
                        null, null, "SHARE", "UNLIMITED", null, null, null, null)));
        when(mapper.deviceSlotCap()).thenReturn(6);
        when(mapper.lockActiveDeviceCount(7L)).thenReturn(4);
        when(mapper.lockReservedTrialSlotCount(7L)).thenReturn(1);

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("stellarbox-s1", "cloud-share"), 1L, new BigDecimal("285"), "bundle-capacity-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("CAPACITY_REPLACEMENT_REQUIRED");
        verify(mapper, never()).decrementStock(anyLong(), anyInt());
        verify(mapper, never()).insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void productionBundleRejectsIncompletePhysicalSpecsBeforeAnyReservationOrStockWrite() {
        AppBundleOrderMapper mapper = mock(AppBundleOrderMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
        when(guard.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.lockUser(7L)).thenReturn(new AppBundleOrderMapper.UserLock(7L, false));
        when(mapper.lockProducts(any())).thenReturn(List.of(
                new AppBundleOrderMapper.ProductRow(1L, "s1", "S1", new BigDecimal("100"), 2,
                        null, null, "DEVICE", "FINITE", null, 16, "500W", "DC-HCM"),
                new AppBundleOrderMapper.ProductRow(2L, "share", "Share", new BigDecimal("20"), 2,
                        null, null, "SHARE", "UNLIMITED", null, null, null, null)));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy,
                mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "share"), 1L, new BigDecimal("285"), "bundle-spec-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("PRODUCT_SPECS_UNAVAILABLE");
        verify(mapper, never()).consumePurchaseQuota(anyString(), anyInt());
        verify(mapper, never()).decrementStock(anyLong(), anyInt());
        verify(mapper, never()).insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any());
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
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), 1L, new BigDecimal("285"), "bundle-key-1");
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
                .create(7L, List.of("stellarbox-s1", "stellarbox-pro"), 1L, new BigDecimal("285"), "mixed-bundle-key");

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
                        new BigDecimal("100"), 2, 0, "GPU", 16, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        "", null, null, 0L, null));
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "pro", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(2L, "pro", "Pro", "Entry",
                        new BigDecimal("200"), 2, 0, "GPU", 16, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        "", null, null, 0L, null));
        when(sandbox.reserveSandboxCatalogStock(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(sandbox.insertSandboxOrder(any())).thenReturn(1);
        when(sandbox.insertInventory(any())).thenReturn(1);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run)
                .create(7L, List.of("s1", "pro"), 1L, new BigDecimal("285"), "sandbox-bundle-key");

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
                .create(7L, List.of("s1", "pro"), 1L, new BigDecimal("285"), "bundle-gate-key");
        assertThat(result.getMessage()).isEqualTo("PURCHASE_GATE_BLOCKED");
        verify(mapper, never()).decrementStock(anyLong(), anyInt());
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
        when(mapper.lockPurchaseGateGeneration("s1")).thenReturn(1L);
        when(mapper.deviceSlotCap()).thenReturn(6);
        when(mapper.attribution(7L)).thenReturn(new AppBundleOrderMapper.Attribution("P1", 1, "2026-W33"));
        when(mapper.decrementStock(anyLong(), anyInt())).thenReturn(1);
        when(mapper.insertBundleOrder(anyLong(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);
        when(mapper.insertBundleItem(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(1);
        when(mapper.snapshotBundleComputePoints(anyString())).thenReturn(1);

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, mock(CommerceAcceptanceSandboxMapper.class), mock(CommerceAcceptanceRun.class))
                .create(7L, List.of("s1", "pro"), 1L, new BigDecimal("285"), "bundle-quota-key");

        assertThat(result.getCode()).isZero();
        verify(mapper).consumePurchaseQuota("s1", 1);
        verify(mapper).insertBundleItem(anyString(),
                argThat((AppBundleOrderMapper.ProductRow product) -> "s1".equals(product.productNo())), eq(1), anyInt(), eq(true), eq(1L));
        verify(mapper).insertBundleItem(anyString(),
                argThat((AppBundleOrderMapper.ProductRow product) -> "pro".equals(product.productNo())), eq(1), anyInt(), eq(false), eq((Long) null));
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
                .create(7L, List.of("s1", "pro"), 1L, new BigDecimal("285"), "bundle-release-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("BUNDLE_PRODUCT_NOT_RELEASED");
        verify(mapper, never()).decrementStock(anyLong(), anyInt());
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
                        new BigDecimal("100"), 2, 0, "GPU", 16, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, "", null, "phase-2", 0L, null));
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "bb", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(2L, "bb", "B", "Entry",
                        new BigDecimal("200"), 2, 0, "GPU", 16, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, "", null, "phase-2", 0L, null));
        when(releasePolicy.evaluate(anyString(), anyString()))
                .thenReturn(StorefrontProductReleasePolicy.Decision.closed("E1_PHASE_NOT_REACHED", "phase-2"));
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run)
                .create(7L, List.of("bb", "aa"), 1L, new BigDecimal("285"), "sandbox-release-key");

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
                        "GPU", 16, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "", null, null, 0L, null));
        when(sandbox.lockSandboxCatalogProduct("test-run-0001", null, "bb", 1)).thenReturn(
                new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(2L, "bb", "B", "Entry", new BigDecimal("200"), 2, 0,
                        "GPU", 16, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "", null, null, 0L, null));
        when(sandbox.reserveSandboxCatalogStock(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(sandbox.insertSandboxOrder(any())).thenReturn(1);
        when(sandbox.insertInventory(any())).thenReturn(1);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());

        var result = new AppBundleOrderService(mapper, idempotency, mock(EventOutboxService.class), guard,
                new StorefrontPurchaseGatePolicy(), releasePolicy, sandbox, run)
                .create(7L, List.of("bb", "aa"), 1L, new BigDecimal("285"), "sandbox-order-lock-key");

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
                            productNo.toUpperCase(), "Entry", price, 2, 0, "GPU", 16, BigDecimal.ONE,
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
        var first = service.create(7L, List.of("bb", "aa"), 1L, new BigDecimal("285"), "same-bundle-key");
        var second = service.create(7L, List.of("bb", "aa"), 1L, new BigDecimal("285"), "same-bundle-key");

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

        assertThatThrownBy(() -> service.create(7L, List.of("aa", "bb"), 1L, new BigDecimal("285"), "missing-run-key"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessageContaining("COMMERCE_SANDBOX_RUN_ID_REQUIRED");
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }
}
