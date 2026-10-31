package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.PolicyRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.WalletRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * FEAT-TRIAL05: the proposal's worked example ($1,299, 3 days, $38.52 + 10 UVT a day, $50 offset cap,
 * $10 early discount, shared promotion switched off so only the early rule differs).
 */
class TrialEarlyPurchaseQuoteTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-28T04:00:00Z");
    private static final LocalDateTime BUSINESS_NOW = LocalDateTime.ofInstant(NOW, SHANGHAI);

    private final SupportPaymentAttributionFacade capture = mock(SupportPaymentAttributionFacade.class);
    private final AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final StorefrontProductReleasePolicy releasePolicy = mock(StorefrontProductReleasePolicy.class);
    private final CanonicalStateMapper canonicalStateMapper = mock(CanonicalStateMapper.class);
    private final EarningsReleaseService earnings = mock(EarningsReleaseService.class);
    private final List<PolicyRow> policies = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        policies.addAll(List.of(new PolicyRow("discountRate", "0"), new PolicyRow("discountCapUSD", "0"),
                new PolicyRow("earlyPurchaseDiscountUSD", "10"), new PolicyRow("seatsLeftToday", "5")));
        when(mapper.policies()).thenReturn(policies);
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("enabled");
        when(releasePolicy.evaluate(anyString(), any())).thenReturn(StorefrontProductReleasePolicy.Decision.open("P1"));
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 2, "2026-W30"));
        when(mapper.trialQuotaRemaining(any(LocalDate.class))).thenReturn(5);
        when(mapper.lockConversionProduct("stellarbox-s1")).thenReturn(product());
        when(mapper.conversionProduct("stellarbox-s1")).thenReturn(product());
        when(mapper.catalogProduct("stellarbox-s1")).thenReturn(product());
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(new BigDecimal("2000"), BigDecimal.ZERO));
        when(mapper.settleWallet(eq(7L), any(), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO))).thenReturn(1);
        when(mapper.decrementProductStock(9L)).thenReturn(1);
        when(mapper.insertConversionOrder(eq(7L), anyString(), eq(9L), any(), any(), any())).thenReturn(1);
        when(mapper.insertConversionOrderItem(anyString(), eq(9L), eq("stellarbox-s1"), anyString(), any())).thenReturn(1);
        when(mapper.insertPurchasedDevice(eq(7L), anyString(), eq(9L), eq("stellarbox-s1"), eq("Entry"),
                eq("DEVICE"), anyString(), eq("Trial"), any())).thenReturn(1);
        when(mapper.deviceIdByInstanceNo(anyString())).thenReturn(77L);
        when(mapper.markRedeemed(eq(1L), eq(0L), eq(77L), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(1);
        when(capture.prepare(anyLong(), any(), anyString())).thenReturn(mock(Prepared.class));
        when(capture.insertLedger(any(Prepared.class), any(), any(), anyString())).thenReturn(1);
        when(earnings.creditRewardInFundOperation(eq(7L), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn("ER-1");
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("120"), new BigDecimal("85")));
    }

    @Test
    void twelveHoursInTheQuoteSettlesTheFullTermAndTakesTheFixedDiscountWithoutTouchingRealAccrual() {
        when(mapper.trial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));

        Map<String, Object> quote = quote(service(NOW));

        assertThat(quote.get("settlementPolicy")).isEqualTo("FULL_TERM_ON_CONVERSION");
        assertThat(quote.get("earlyPurchase")).isEqualTo(true);
        assertThat(quote).containsEntry("accruedUsdt", new BigDecimal("19.260000"))
                .containsEntry("accruedNex", new BigDecimal("5.000000"))
                .containsEntry("settledUsdt", new BigDecimal("115.560000"))
                .containsEntry("rewardNex", new BigDecimal("30.000000"))
                .containsEntry("earlyRewardUsdt", new BigDecimal("96.300000"))
                .containsEntry("earlyRewardNex", new BigDecimal("25.000000"))
                .containsEntry("offsetUsdt", new BigDecimal("50.000000"))
                .containsEntry("remainderUsdt", new BigDecimal("65.560000"))
                .containsEntry("earlyDiscountUsdt", new BigDecimal("10.000000"))
                .containsEntry("amountUsdt", new BigDecimal("1239.000000"));
        assertThat(quote.get("earlyEndsAtEpochMs")).isNotNull();
    }

    @Test
    void earlyQuoteIsTheSameAtAnyPointOfTheOriginalTerm() {
        when(mapper.trial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        Map<String, Object> early = quote(service(NOW));
        Map<String, Object> later = quote(service(NOW.plusSeconds(36 * 3600)));

        assertThat(later.get("quoteVersion")).isEqualTo(early.get("quoteVersion"));
        assertThat(later.get("amountUsdt")).isEqualTo(early.get("amountUsdt"));
        assertThat(later.get("accruedUsdt")).isEqualTo(new BigDecimal("77.040000"));
    }

    @Test
    void waitingForExpirySettlesTheSameTermButLosesOnlyTheFixedDiscount() {
        when(mapper.trial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        Map<String, Object> afterExpiry = quote(service(NOW.plusSeconds(61 * 3600)));

        assertThat(afterExpiry.get("settlementPolicy")).isEqualTo("ACCRUED_ON_CONVERSION");
        assertThat(afterExpiry.get("quoteVersion")).isEqualTo("ACCRUED_ON_CONVERSION");
        assertThat(afterExpiry).containsEntry("settledUsdt", new BigDecimal("115.560000"))
                .containsEntry("rewardNex", new BigDecimal("30.000000"))
                .containsEntry("remainderUsdt", new BigDecimal("65.560000"))
                .containsEntry("earlyDiscountUsdt", new BigDecimal("0.000000"))
                .containsEntry("amountUsdt", new BigDecimal("1249.000000"));
    }

    @Test
    void anExtendedClaimNeverEarnsTheEarlyRuleEvenWithAFutureExpiry() {
        when(mapper.trial(7L)).thenReturn(trial("EXTENDED", 96, new BigDecimal("1299")));
        Map<String, Object> quote = quote(service(NOW));
        assertThat(quote.get("earlyPurchase")).isEqualTo(false);
        assertThat(quote.get("earlyDiscountUsdt")).isEqualTo(new BigDecimal("0.000000"));
    }

    @Test
    void zeroSwitchesTheWholeEarlyRuleOffAndKeepsTheAccruedQuote() {
        policies.set(2, new PolicyRow("earlyPurchaseDiscountUSD", "0"));
        when(mapper.trial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        Map<String, Object> quote = quote(service(NOW));
        assertThat(quote.get("settlementPolicy")).isEqualTo("ACCRUED_ON_CONVERSION");
        assertThat(quote).containsEntry("rewardNex", new BigDecimal("5.000000"))
                .containsEntry("offsetUsdt", new BigDecimal("19.260000"))
                .containsEntry("amountUsdt", new BigDecimal("1279.740000"));
    }

    @Test
    void theFixedDiscountStopsAtWhatIsLeftToPay() {
        when(mapper.trial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("55")));
        Map<String, Object> quote = quote(service(NOW));
        assertThat(quote).containsEntry("earlyDiscountConfiguredUsdt", new BigDecimal("10.00"))
                .containsEntry("earlyDiscountUsdt", new BigDecimal("5.000000"))
                .containsEntry("amountUsdt", new BigDecimal("0.000000"));
    }

    @Test
    void aClaimWithoutACompleteSnapshotKeepsTheAccruedRule() {
        TrialRow row = trial("ACTIVE", 12, new BigDecimal("1299"));
        when(mapper.trial(7L)).thenReturn(new TrialRow(row.id(), row.userId(), row.claimNo(), row.status(), null, null,
                row.deviceName(), null, row.dailyUsdt(), row.dailyNex(), row.offsetCapUsdt(), row.priceUsdt(),
                row.quotaSnapshot(), row.claimedAt(), row.expiresAt(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0L));
        Map<String, Object> quote = quote(service(NOW));
        assertThat(quote.get("settlementPolicy")).isEqualTo("ACCRUED_ON_CONVERSION");
        assertThat(quote.get("earlyDiscountUsdt")).isEqualTo(new BigDecimal("0.000000"));
    }

    @Test
    void earlyConversionPaysTheQuotedAmountAndCreditsTheFullTermRewardsOnce() {
        when(mapper.trial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        when(mapper.lockTrial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        AppTrialLifecycleService service = service(NOW);
        String version = (String) quote(service).get("quoteVersion");

        ApiResult<Map<String, Object>> result = service.convert(7L, "stellarbox-s1", new BigDecimal("1239.00"),
                version, "early-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("amountUsdt", new BigDecimal("1239.000000"))
                .containsEntry("discountUsdt", new BigDecimal("60.000000"))
                .containsEntry("shadowUsdt", new BigDecimal("19.260000"))
                .containsEntry("shadowNex", new BigDecimal("5.000000"))
                .containsEntry("remainderUsdt", new BigDecimal("65.560000"))
                .containsEntry("rewardNex", new BigDecimal("30.000000"))
                .containsEntry("earlyDiscountUsdt", new BigDecimal("10.000000"))
                .containsEntry("earlyPurchase", true)
                .containsEntry("quoteVersion", version);
        verify(mapper).settleWallet(7L, new BigDecimal("1239.000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        verify(earnings).creditRewardInFundOperation(7L, "H2_TRIAL_REMAINDER", "TRIAL-1:REMAINDER", "USDT",
                new BigDecimal("65.560000"), "H2:TRIAL-1:REMAINDER:USDT");
        verify(earnings).creditRewardInFundOperation(7L, "H2_TRIAL_BONUS", "TRIAL-1:NEX", "UVT",
                new BigDecimal("30.000000"), "H2:TRIAL-1:NEX");
        verify(mapper).insertConversionOrder(eq(7L), anyString(), eq(9L), eq(new BigDecimal("1299")),
                eq(new BigDecimal("60.000000")), eq(new BigDecimal("1239.000000")));
        verify(mapper).markRedeemed(eq(1L), eq(0L), eq(77L), eq(new BigDecimal("19.260000")),
                eq(new BigDecimal("5.000000")), eq(new BigDecimal("65.560000")), eq(new BigDecimal("30.000000")),
                eq(new BigDecimal("60.000000")), eq(new BigDecimal("1239.000000")), any(),
                argThat(snapshot -> snapshot.contains("settlementPolicy=FULL_TERM_ON_CONVERSION")
                        && snapshot.contains("earlyRewardNex=25.000000") && snapshot.length() <= 1000));
    }

    @Test
    void aConfirmedEarlyQuoteIsNotSilentlyReplacedOnceTheTermEnds() {
        when(mapper.trial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        String version = (String) quote(service(NOW)).get("quoteVersion");
        when(mapper.lockTrial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        AppTrialLifecycleService expired = service(NOW.plusSeconds(61 * 3600));

        ApiResult<Map<String, Object>> sameAmount = expired.convert(7L, "stellarbox-s1",
                new BigDecimal("1249.00"), version, "late-1");
        ApiResult<Map<String, Object>> oldAmount = expired.convert(7L, "stellarbox-s1",
                new BigDecimal("1239.00"), version, "late-2");

        assertThat(sameAmount.getMessage()).isEqualTo("TRIAL_QUOTE_CHANGED");
        assertThat(oldAmount.getMessage()).isEqualTo("TRIAL_AMOUNT_MISMATCH");
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(earnings, never()).creditRewardInFundOperation(anyLong(), anyString(), anyString(), anyString(), any(),
                anyString());
    }

    @Test
    void coverageBelowRedlineBlocksTheEarlyRewardsBeforeAnyCashMoves() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("85")));
        when(mapper.lockTrial(7L)).thenReturn(trial("ACTIVE", 12, new BigDecimal("1299")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(NOW).convert(7L, "stellarbox-s1",
                new BigDecimal("1239.00"), null, "early-redline")).hasMessage("B1_COVERAGE_REDLINE_BLOCKED");
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> quote(AppTrialLifecycleService service) {
        ApiResult<Map<String, Object>> state = service.state(7L);
        assertThat(state.getCode()).isZero();
        return (Map<String, Object>) state.getData().get("quote");
    }

    private AppTrialLifecycleService service(Instant at) {
        return ffdd.opsconsole.auth.support.AuthorizedPurchaseFixture.businessOnly(new AppTrialLifecycleService(capture, mapper, earnings, idempotency, coverage, audit, outbox,
                releasePolicy, canonicalStateMapper, environment(), Clock.fixed(at, SHANGHAI)));
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    /** Claimed {@code hoursAgo} before NOW; ACTIVE claims expire after the original 3 days. */
    @Test
    void aRedeemedTrialReportsItsOrderAndWhetherThatOrderWasRefunded() {
        when(mapper.trial(7L)).thenReturn(trial("REDEEMED", 12, new BigDecimal("1299")));
        when(mapper.settlementSnapshot(1L)).thenReturn(
                "trigger=convert,orderNo=TRC-1,amountUsdt=1189,settlementPolicy=FULL_TERM_ON_CONVERSION");
        when(mapper.orderPaymentStatus("TRC-1", 7L)).thenReturn("REFUNDED");

        Map<String, Object> state = service(NOW).state(7L).getData();

        assertThat(state).containsEntry("conversionOrderNo", "TRC-1").containsEntry("conversionRefunded", true)
                .containsEntry("conversionRewardsReversed", true);

        when(mapper.settlementSnapshot(1L)).thenReturn("trigger=convert,orderNo=TRC-1,amountUsdt=1199");
        assertThat(service(NOW).state(7L).getData()).containsEntry("conversionRefunded", true)
                .containsEntry("conversionRewardsReversed", false);

        when(mapper.orderPaymentStatus("TRC-1", 7L)).thenReturn("PAID");
        assertThat(service(NOW).state(7L).getData()).containsEntry("conversionRefunded", false);
    }

    @Test
    void aRedeemedTrialWithoutAnOrderInItsSnapshotNamesNoOrder() {
        when(mapper.trial(7L)).thenReturn(trial("REDEEMED", 12, new BigDecimal("1299")));
        when(mapper.settlementSnapshot(1L)).thenReturn("trigger=convert");

        Map<String, Object> state = service(NOW).state(7L).getData();

        assertThat(state).containsEntry("conversionOrderNo", null).containsEntry("conversionRefunded", false);
    }

    private static TrialRow trial(String status, long hoursAgo, BigDecimal price) {
        LocalDateTime claimedAt = BUSINESS_NOW.minusHours(hoursAgo);
        LocalDateTime expiresAt = "EXTENDED".equals(status) ? BUSINESS_NOW.plusDays(2) : claimedAt.plusDays(3);
        return new TrialRow(1L, 7L, "TRIAL-1", status, null, null, "Trial", 3,
                new BigDecimal("38.52"), new BigDecimal("10"), new BigDecimal("50"), price,
                "productCode=stellarbox-s1", claimedAt, expiresAt,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0L);
    }

    private static AppTrialLifecycleMapper.ConversionProduct product() {
        return new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "Trial", "Entry",
                new BigDecimal("1299"), 5, "P1", "DEVICE", "FINITE", new BigDecimal("1"), new BigDecimal("1"));
    }
}
