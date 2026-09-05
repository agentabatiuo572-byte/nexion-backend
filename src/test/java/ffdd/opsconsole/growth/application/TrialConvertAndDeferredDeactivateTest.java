package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TrialConvertAndDeferredDeactivateTest {
    private static final Clock TEST_CLOCK = Clock.fixed(
            Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);
    private static final BigDecimal EXPECTED_AMOUNT = new BigDecimal("1277.33");
    private final AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final StorefrontProductReleasePolicy productReleasePolicy = mock(StorefrontProductReleasePolicy.class);
    private final CanonicalStateMapper canonicalStateMapper = mock(CanonicalStateMapper.class);
    private final AppTrialLifecycleService service = new AppTrialLifecycleService(
            mapper, earningsRelease, idempotency, coverage,
            mock(AuditLogService.class), mock(EventOutboxService.class), productReleasePolicy, canonicalStateMapper, productionEnvironment(),
            TEST_CLOCK);

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setup() {
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("enabled");
        when(productReleasePolicy.evaluate(anyString(), any()))
                .thenReturn(StorefrontProductReleasePolicy.Decision.open("P1"));
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.policies()).thenReturn(List.of(
                new AppTrialLifecycleMapper.PolicyRow("phaseOpen", "true"),
                new AppTrialLifecycleMapper.PolicyRow("trialProductId", "stellarbox-s1"),
                new AppTrialLifecycleMapper.PolicyRow("trialOffsetCapUSD", "50"),
                new AppTrialLifecycleMapper.PolicyRow("discountRate", "0.15"),
                new AppTrialLifecycleMapper.PolicyRow("discountCapUSD", "20")));
        when(mapper.attribution(7L)).thenReturn(new AppTrialLifecycleMapper.Attribution("P1", 1, "2026-W30"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("85")));
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void convertLocksAuthoritativeProductCreatesOrderAndClosesActiveTrialAtomically() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(mapper.lockConversionProduct("stellarbox-s1"))
                .thenReturn(new AppTrialLifecycleMapper.ConversionProduct(11L, "stellarbox-s1", "S1", new BigDecimal("1299"), 2, "P1"));
        when(mapper.decrementProductStock(11L)).thenReturn(1);
        when(mapper.lockWallet(7L)).thenReturn(new AppTrialLifecycleMapper.WalletRow(
                new BigDecimal("2000"), BigDecimal.ZERO));
        when(mapper.settleWallet(eq(7L), any(), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO))).thenReturn(1);
        when(mapper.insertConversionOrder(eq(7L), anyString(), eq(11L), any(), any(), any())).thenReturn(1);
        when(mapper.insertConversionOrderItem(anyString(), eq(11L), eq("stellarbox-s1"), eq("S1"), any())).thenReturn(1);
        when(mapper.insertPurchasedDevice(eq(7L), anyString(), eq(11L), eq("stellarbox-s1"), any(),
                eq("DEVICE"), anyString(), eq("NexGridBox S1"), eq(new BigDecimal("1299")), any(), any()))
                .thenReturn(1);
        when(mapper.deviceIdByInstanceNo(anyString())).thenReturn(77L);
        when(mapper.markRedeemed(eq(1L), eq(0L), eq(77L), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(1);

        ApiResult<java.util.Map<String, Object>> result = service.convert(
                7L, "stellarbox-s1", EXPECTED_AMOUNT, "convert-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("orderStatus", "PAID")
                .containsEntry("paymentStatus", "PAID")
                .containsEntry("source", "nx_trial_claim + nx_order + nx_order_item")
                .containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        assertThat(result.getData().get("provenance"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("source", "nx_trial_claim + nx_order + nx_order_item")
                .containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        verify(mapper).lockConversionProduct("stellarbox-s1");
        verify(mapper).insertConversionOrder(eq(7L), anyString(), eq(11L),
                eq(new BigDecimal("1299")), eq(new BigDecimal("21.666666")),
                eq(new BigDecimal("1277.333334")));
        verify(mapper).markRedeemed(eq(1L), eq(0L), eq(77L), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void convertRejectsDifferentProductOrUnavailableStockBeforeAnyMutation() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(mapper.lockConversionProduct("other-product")).thenReturn(null);

        ApiResult<java.util.Map<String, Object>> result = service.convert(
                7L, "other-product", EXPECTED_AMOUNT, "convert-2");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_PRODUCT_NOT_ELIGIBLE");
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).markRedeemed(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void convertRejectsZeroStockBeforeAnyMutation() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(mapper.lockConversionProduct("stellarbox-s1"))
                .thenReturn(new AppTrialLifecycleMapper.ConversionProduct(
                        11L, "stellarbox-s1", "S1", new BigDecimal("1299"), 0, "P1"));

        ApiResult<java.util.Map<String, Object>> result = service.convert(
                7L, "stellarbox-s1", EXPECTED_AMOUNT, "convert-stock-zero");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_PRODUCT_NOT_AVAILABLE");
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).markRedeemed(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void convertRejectsAmountAboveTheConfirmedQuoteBeforeAnyMutation() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(mapper.lockConversionProduct("stellarbox-s1"))
                .thenReturn(new AppTrialLifecycleMapper.ConversionProduct(
                        11L, "stellarbox-s1", "S1", new BigDecimal("1299"), 2, "P1"));

        ApiResult<java.util.Map<String, Object>> result = service.convert(
                7L, "stellarbox-s1", new BigDecimal("1277.32"), "convert-price-changed");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_AMOUNT_MISMATCH");
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).markRedeemed(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void convertRejectsMissingOrOverPreciseExpectedAmountBeforeLockingState() {
        ApiResult<java.util.Map<String, Object>> missing = service.convert(
                7L, "stellarbox-s1", null, "convert-missing-amount");
        ApiResult<java.util.Map<String, Object>> overPrecise = service.convert(
                7L, "stellarbox-s1", new BigDecimal("1277.333"), "convert-precise-amount");

        assertThat(missing.getMessage()).isEqualTo("TRIAL_AMOUNT_INVALID");
        assertThat(overPrecise.getMessage()).isEqualTo("TRIAL_AMOUNT_INVALID");
        verify(mapper, never()).lockTrial(anyLong());
        verify(mapper, never()).decrementProductStock(anyLong());
    }

    private TrialRow activeTrial() {
        LocalDateTime now = LocalDateTime.ofInstant(TEST_CLOCK.instant(), ZoneId.of("Asia/Shanghai"));
        return new TrialRow(1L, 7L, "TRIAL-1", "ACTIVE", null, null, "NexGridBox S1", 3,
                new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"), new BigDecimal("1299"),
                "productCode=stellarbox-s1", now.minusHours(1), now.plusDays(2), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, 0L);
    }
}
