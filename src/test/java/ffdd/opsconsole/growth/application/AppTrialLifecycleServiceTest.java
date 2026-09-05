package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.PolicyRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.WalletRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppTrialLifecycleServiceTest {
    private final AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final StorefrontProductReleasePolicy productReleasePolicy = mock(StorefrontProductReleasePolicy.class);
    private final CanonicalStateMapper canonicalStateMapper = mock(CanonicalStateMapper.class);
    private final EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
    private final MockEnvironment environment = productionEnvironment();
    private final AppTrialLifecycleService service = new AppTrialLifecycleService(
            mapper, earningsRelease, idempotency, coverage, audit, outbox, productReleasePolicy, canonicalStateMapper, environment,
            Clock.system(ZoneId.of("Asia/Shanghai")));

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("enabled");
        when(productReleasePolicy.evaluate(anyString(), any()))
                .thenReturn(StorefrontProductReleasePolicy.Decision.open("P1"));
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialDays", "3"),
                new PolicyRow("shadowDailyUSD", "40"),
                new PolicyRow("shadowDailyNEX", "5"),
                new PolicyRow("trialOffsetCapUSD", "50"),
                new PolicyRow("trialPriceUSD", "1299"),
                new PolicyRow("seatsLeftToday", "47")));
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 2, "2026-W30"));
        when(mapper.consumeTrialQuota(any(LocalDate.class))).thenReturn(1);
        when(mapper.trialQuotaRemaining(any(LocalDate.class))).thenReturn(47, 46);
        when(mapper.lockConversionProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "NexGridBox S1", "Entry",
                        new BigDecimal("1299"), 5, "P1", "DEVICE", "FINITE"));
        when(mapper.lockTrialStartProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "NexGridBox S1", "Entry",
                        new BigDecimal("1299"), 5, "P1", "DEVICE", "FINITE"));
        when(mapper.conversionProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "NexGridBox S1", "Entry",
                        new BigDecimal("1299"), 5, "P1", "DEVICE", "FINITE"));
        when(mapper.catalogProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "NexGridBox S1", "Entry",
                        new BigDecimal("1299"), 5, "P1", "DEVICE", "FINITE"));
        when(mapper.failTrial(anyLong(), anyLong(), any(), any(), any(), any(), anyString())).thenReturn(1);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("85")));
    }

    @Test
    void autoPushKillOverridesEnabledPolicyWithoutDisablingManualTrialEntry() {
        when(mapper.autoPushKilled()).thenReturn("true");
        Map<String, Object> state = service.state(7L).getData();
        assertThat(((Map<?, ?>) state.get("config")).get("autoPushEnabled")).isEqualTo(false);
        assertThat(state.get("trialGateEnabled")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateProjectsAnUninitializedDayWithoutWritingQuota() {
        when(mapper.trialQuotaRemaining(any(LocalDate.class))).thenReturn(null);
        Map<String, Object> state = service.state(7L).getData();
        assertThat((Map<String, String>) state.get("config")).containsEntry("seatsLeftToday", "47");
        verify(mapper, never()).ensureTrialQuotaDay(any(LocalDate.class), anyInt());
        verify(mapper, never()).consumeTrialQuota(any(LocalDate.class));
    }

    @Test
    void missingAutoPushKillPreservesPolicySetting() {
        Map<String, Object> state = service.state(7L).getData();
        assertThat(((Map<?, ?>) state.get("config")).get("autoPushEnabled")).isEqualTo(true);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"false", "0", "no", "off", "disabled", "inactive", "", " ", "garbage"})
    void autoPushKillMatchesPcFalseAndUnknownValues(String value) {
        when(mapper.autoPushKilled()).thenReturn(value);
        assertThat(((Map<?, ?>) service.state(7L).getData().get("config")).get("autoPushEnabled")).isEqualTo(true);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"true", "1", "yes", "on", "enabled", "ACTIVE"})
    void autoPushKillMatchesEveryPcTrueAlias(String value) {
        when(mapper.autoPushKilled()).thenReturn(value);
        assertThat(((Map<?, ?>) service.state(7L).getData().get("config")).get("autoPushEnabled")).isEqualTo(false);
    }

    @Test
    void stateRejectsDeletedOrInactiveAuthenticatedIdentity() {
        when(mapper.activeUser(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("USER_NOT_FOUND");
        verify(mapper, never()).trial(7L);
    }

    @Test
    void trialCycleSignalBlocksBeforeClaimMutation() {
        when(mapper.trialCycleSignalCount(7L)).thenReturn(1L);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-cycle");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_CYCLE_RISK_BLOCKED");
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void j1TrialGateBlocksOnlyNewEntryBeforeAnyTrialMutation() {
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("disabled");

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-j1-kill");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_KILL_SWITCH_DISABLED");
        verify(mapper, never()).trialCycleSignalCount(anyLong());
        verify(mapper, never()).lockTrial(anyLong());
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void j1TrialGateBlocksConversionRedeemAndAutomaticChargeBeforeAnyMoneyOrInventorySideEffect() {
        TrialRow active = activeTrial();
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("disabled");
        when(mapper.lockTrial(7L)).thenReturn(active);

        ApiResult<Map<String, Object>> convert = service.convert(
                7L, "stellarbox-s1", new BigDecimal("1299.00"), "h2-j1-convert");
        ApiResult<Map<String, Object>> redeem = service.redeemEarly(7L, "h2-j1-redeem");
        ApiResult<Map<String, Object>> charge = service.charge(7L, "h2-j1-charge");
        ApiResult<Map<String, Object>> settleDue = service.settleDue(7L, "TRIAL-1", "h2-j1-scheduled");

        assertThat(convert.getMessage()).isEqualTo("TRIAL_KILL_SWITCH_DISABLED");
        assertThat(redeem.getMessage()).isEqualTo("TRIAL_KILL_SWITCH_DISABLED");
        assertThat(charge.getMessage()).isEqualTo("TRIAL_KILL_SWITCH_DISABLED");
        assertThat(settleDue.getMessage()).isEqualTo("TRIAL_KILL_SWITCH_DISABLED");
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertConversionOrderItem(anyString(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertPurchasedDevice(anyLong(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any());
        verify(mapper, never()).markRedeemed(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), anyString());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void futureE1UnlockPhaseBlocksStartAndConvertWhileRetiredRedeemRequiresExplicitPurchase() {
        when(mapper.lockTrial(7L)).thenReturn(null, activeTrial(), activeTrial());
        when(productReleasePolicy.evaluate(eq("stellarbox-s1"), eq("P1")))
                .thenReturn(StorefrontProductReleasePolicy.Decision.closed("E1_PHASE_NOT_REACHED", "P1"));

        ApiResult<Map<String, Object>> start = service.start(7L, null, "Trial", "h2-e1-start");
        ApiResult<Map<String, Object>> convert = service.convert(
                7L, "stellarbox-s1", new BigDecimal("1299.00"), "h2-e1-convert");
        ApiResult<Map<String, Object>> redeem = service.redeemEarly(7L, "h2-e1-redeem");

        assertThat(start.getMessage()).isEqualTo("TRIAL_PRODUCT_NOT_RELEASED");
        assertThat(convert.getMessage()).isEqualTo("TRIAL_PRODUCT_NOT_RELEASED");
        assertThat(redeem.getCode()).isEqualTo(410);
        assertThat(redeem.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertPurchasedDevice(anyLong(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void retiredEarlyRedeemRejectsBeforeCapacityWalletInventoryOrderOrDeviceChecks() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(canonicalStateMapper.activeDeviceCount(7L)).thenReturn(5);
        when(canonicalStateMapper.reservedDeviceOrderCount(7L)).thenReturn(1);
        when(canonicalStateMapper.deviceSlotCap()).thenReturn(6);

        ApiResult<Map<String, Object>> result = service.redeemEarly(7L, "h2-capacity-full");

        assertThat(result.getCode()).isEqualTo(410);
        assertThat(result.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertPurchasedDevice(anyLong(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any());
        verify(mapper, never()).lockTrial(anyLong());
        verify(canonicalStateMapper, never()).activeDeviceCount(anyLong());
        verify(canonicalStateMapper, never()).reservedDeviceOrderCount(anyLong());
    }

    @Test
    void fullPhysicalCapacityAlsoBlocksExplicitConversionBeforeWalletInventoryOrderOrDeviceMutation() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(canonicalStateMapper.activeDeviceCount(7L)).thenReturn(5);
        when(canonicalStateMapper.reservedDeviceOrderCount(7L)).thenReturn(1);
        when(canonicalStateMapper.deviceSlotCap()).thenReturn(6);

        ApiResult<Map<String, Object>> result = service.convert(
                7L, "stellarbox-s1", new BigDecimal("1299.00"), "h2-convert-capacity-full");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("CAPACITY_REPLACEMENT_REQUIRED");
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertPurchasedDevice(anyLong(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void retiredEarlyRedeemRejectsWithoutInspectingTheRequestedProductOrCapacity() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(mapper.lockConversionProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "Share", "Share",
                        new BigDecimal("1299"), 1, "P1", "SHARE", "FINITE"));
        ApiResult<Map<String, Object>> result = service.redeemEarly(7L, "h2-share-capacity");

        assertThat(result.getCode()).isEqualTo(410);
        assertThat(result.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        verify(mapper, never()).lockTrial(anyLong());
        verify(mapper, never()).lockConversionProduct(anyString());
        verify(canonicalStateMapper, never()).activeDeviceCount(anyLong());
        verify(canonicalStateMapper, never()).reservedDeviceOrderCount(anyLong());
    }

    @Test
    void stateProjectsJ1TrialGateAndDisablesOnlyEligibility() {
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("disabled");
        when(mapper.trial(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "ELIGIBLE")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "phase-closed")
                .containsEntry("trialGateEnabled", false);
    }

    @Test
    void stateProjectsRiskBlockedEligibilityWithoutTrustingClientStorage() {
        when(mapper.trial(7L)).thenReturn(null);
        when(mapper.trialCycleSignalCount(7L)).thenReturn(1L);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "ELIGIBLE")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "risk")
                .containsEntry("authoritative", true)
                .containsKey("serverNowEpochMs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stateProjectsPcManagedFreeTrialCardQuota() {
        when(mapper.trial(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat((Map<String, Object>) result.getData().get("config"))
                .containsEntry("seatsLeftToday", "47");
        assertThat(result.getData())
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "nx_trial_claim")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        assertThat((Map<String, Object>) result.getData().get("provenance"))
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "nx_trial_claim")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
    }

    @Test
    void exhaustedQuotaDisablesEligibilityAndCannotBeClaimed() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "device-trial-standard"),
                new PolicyRow("seatsLeftToday", "0")));
        when(mapper.trialQuotaRemaining(any(LocalDate.class))).thenReturn(0);

        ApiResult<Map<String, Object>> state = service.state(7L);
        ApiResult<Map<String, Object>> start = service.start(7L, null, "Trial", "h2-quota-empty");

        assertThat(state.getData())
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "quota-exhausted");
        assertThat(start.getCode()).isEqualTo(409);
        assertThat(start.getMessage()).isEqualTo("TRIAL_QUOTA_EXHAUSTED");
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void concurrentQuotaLossFailsClosedBeforeCreatingTrial() {
        when(mapper.consumeTrialQuota(any(LocalDate.class))).thenReturn(0);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-quota-race");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_QUOTA_EXHAUSTED");
        verify(mapper).consumeTrialQuota(any(LocalDate.class));
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulClaimProjectsTheDatabaseRemainingQuotaAfterAtomicConsumption() {
        when(mapper.trialQuotaRemaining(any(LocalDate.class))).thenReturn(2, 0);
        when(mapper.insertTrial(anyLong(), anyString(), anyString(), isNull(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString())).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-quota-exact");

        assertThat(result.getCode()).isZero();
        assertThat((Map<String, Object>) result.getData().get("config"))
                .containsEntry("seatsLeftToday", "0");
        verify(mapper).consumeTrialQuota(any(LocalDate.class));
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @Test
    void quotaDayUsesAsiaShanghaiEvenWhenInjectedClockHasAnotherZone() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T16:30:00Z"), ZoneId.of("Pacific/Honolulu"));
        AppTrialLifecycleService shanghaiService = new AppTrialLifecycleService(
                mapper, earningsRelease, idempotency, coverage, audit, outbox, productReleasePolicy, canonicalStateMapper, environment, clock);

        ApiResult<Map<String, Object>> result = shanghaiService.state(7L);

        assertThat(result.getCode()).isZero();
        verify(mapper, never()).ensureTrialQuotaDay(any(LocalDate.class), anyInt());
        verify(mapper).trialQuotaRemaining(LocalDate.of(2026, 8, 21));
    }

    @Test
    void aStartCrossingMidnightConsumesTheSameQuotaDateItInitialized() {
        Clock crossing = mock(Clock.class);
        when(crossing.instant()).thenReturn(Instant.parse("2026-08-20T15:59:59Z"), Instant.parse("2026-08-20T16:00:01Z"));
        when(mapper.insertTrial(anyLong(), anyString(), anyString(), isNull(), anyString(), anyInt(),
                any(), any(), any(), any(), any(), any(), anyString())).thenReturn(1);
        var crossingService = new AppTrialLifecycleService(mapper, earningsRelease, idempotency, coverage,
                audit, outbox, productReleasePolicy, canonicalStateMapper, environment, crossing);
        assertThat(crossingService.start(7L, null, "ignored", "midnight-start").getCode()).isZero();
        verify(mapper).ensureTrialQuotaDay(LocalDate.of(2026, 8, 20), 47);
        verify(mapper).consumeTrialQuota(LocalDate.of(2026, 8, 20));
        verify(mapper, never()).consumeTrialQuota(LocalDate.of(2026, 8, 21));
    }

    @Test
    void unknownMixedOrMissingProfilesFailClosedBeforeAnyUserLookup() {
        for (String[] profiles : new String[][] {{"test"}, {"dev", "prod"}, {}}) {
            AppTrialLifecycleMapper isolatedMapper = mock(AppTrialLifecycleMapper.class);
            MockEnvironment forbidden = new MockEnvironment();
            forbidden.setActiveProfiles(profiles);
            AppTrialLifecycleService denied = new AppTrialLifecycleService(
                    isolatedMapper, earningsRelease, idempotency, coverage, audit, outbox, productReleasePolicy, canonicalStateMapper, forbidden,
                    Clock.systemUTC());

            ApiResult<Map<String, Object>> result = denied.state(7L);

            assertThat(result.getCode()).isEqualTo(404);
            verify(isolatedMapper, never()).activeUser(anyLong());
            verify(isolatedMapper, never()).activeDevelopmentUser(anyLong(), anyString(), anyString());
        }
    }

    @Test
    void configuredTrialProductMapsToTheServerOwnedTrialDeviceInsteadOfClientInput() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "device-trial-standard"),
                new PolicyRow("trialDays", "3"),
                new PolicyRow("shadowDailyUSD", "40"),
                new PolicyRow("shadowDailyNEX", "5"),
                new PolicyRow("trialOffsetCapUSD", "50"),
                new PolicyRow("trialPriceUSD", "1299"),
                new PolicyRow("seatsLeftToday", "47")));
        when(mapper.insertTrial(anyLong(), anyString(), anyString(), isNull(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString())).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "untrusted-client-name", "h2-product-map");

        assertThat(result.getCode()).isZero();
        verify(mapper).insertTrial(eq(7L), anyString(), eq("h2-product-map"), isNull(),
                eq("NexGridBox S1"), anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void claimNeverConsumesQuotaWhenTheE1TargetProductIsUnavailable() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "stellarbox-s1"),
                new PolicyRow("trialDays", "3"),
                new PolicyRow("shadowDailyUSD", "40"),
                new PolicyRow("shadowDailyNEX", "5"),
                new PolicyRow("trialOffsetCapUSD", "50"),
                new PolicyRow("trialPriceUSD", "1"),
                new PolicyRow("seatsLeftToday", "47")));
        when(mapper.lockTrialStartProduct("stellarbox-s1")).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "untrusted", "h2-e1-unavailable");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_PRODUCT_NOT_AVAILABLE");
        verify(mapper, never()).consumeTrialQuota(any(LocalDate.class));
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void unknownConfiguredTrialProductFailsClosedForReadAndStart() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "device-trial-unknown")));
        when(mapper.trial(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> state = service.state(7L);
        ApiResult<Map<String, Object>> start = service.start(7L, null, "untrusted-client-name", "h2-product-invalid");

        assertThat(state.getData()).containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "product-unavailable");
        assertThat(start.getCode()).isEqualTo(409);
        assertThat(start.getMessage()).isEqualTo("TRIAL_PRODUCT_NOT_AVAILABLE");
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void activeClaimKeepsItsLifecycleReasonWhenTheCatalogProductLaterSellsOut() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "stellarbox-s1"),
                new PolicyRow("seatsLeftToday", "47")));
        when(mapper.trial(7L)).thenReturn(activeTrial());
        when(mapper.conversionProduct("stellarbox-s1")).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "ACTIVE")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "in-progress");
    }

    @Test
    void activeClaimKeepsTheProductPinnedAtStartWhenH2ChangesTheNewTrialTarget() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "stellarbox-pro"),
                new PolicyRow("trialPriceUSD", "2199"),
                new PolicyRow("seatsLeftToday", "47")));
        when(mapper.trial(7L)).thenReturn(activeTrial());
        when(mapper.catalogProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "NexGridBox S1", "Entry",
                        new BigDecimal("1499"), 0, "P1", "DEVICE", "FINITE"));

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getData().get("config"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("trialProductId", "stellarbox-s1")
                .containsEntry("trialProductName", "Trial")
                .containsEntry("trialPriceUSD", "1299");
    }

    @Test
    void stateNormalizesTrialBooleanPolicyEnumsToActualBooleanDtoValues() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "开放"),
                new PolicyRow("autoPushEnabled", "开"),
                new PolicyRow("autoChargeAtEnd", "关")));
        when(mapper.trial(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getData().get("config"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("phaseOpen", true)
                .containsEntry("autoPushEnabled", true)
                .containsEntry("autoChargeAtEnd", false);
    }

    @Test
    void stateProjectsCorruptPersistedStatusAsUnknownAndNeverEligible() {
        TrialRow corrupt = trialWithStatus(" CORRUPT ", null, 4L);
        when(mapper.trial(7L)).thenReturn(corrupt);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "CORRUPT")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "unknown")
                .containsEntry("version", 4L);
        verify(mapper, never()).restartTrial(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void startRejectsUnknownPersistedStatusBeforeAnyOverwrite() {
        TrialRow corrupt = trialWithStatus(null, null, 4L);
        when(mapper.lockTrial(7L)).thenReturn(corrupt);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-corrupt");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_STATE_UNKNOWN");
        verify(mapper, never()).restartTrial(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void legacyCancelledTerminalRemainsTheOnlyRestartableFamilyAfterCooldown() {
        TrialRow cancelled = trialWithStatus("CANCELLED", LocalDateTime.now().minusDays(1), 4L);
        TrialRow restarted = trialWithStatus("ACTIVE", null, 5L);
        when(mapper.lockTrial(7L)).thenReturn(cancelled);
        when(mapper.restartTrial(eq(1L), eq(4L), anyString(), eq("h2-legacy-cancelled"),
                any(), anyString(), anyInt(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(1);
        when(mapper.trial(7L)).thenReturn(restarted);

        ApiResult<Map<String, Object>> result = service.start(
                7L, null, "Trial", "h2-legacy-cancelled");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "ACTIVE")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "in-progress");
        verify(mapper).restartTrial(eq(1L), eq(4L), anyString(), eq("h2-legacy-cancelled"),
                any(), anyString(), anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void redeemedTrialCanNeverBeRestarted() {
        TrialRow redeemed = new TrialRow(1L, 7L, "TRIAL-USED", "REDEEMED", 9L, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), "productCode=stellarbox-s1", LocalDateTime.now().minusDays(3), LocalDateTime.now(),
                new BigDecimal("120"), new BigDecimal("15"), new BigDecimal("70"),
                new BigDecimal("50"), new BigDecimal("1199"), null, 2L);
        when(mapper.lockTrial(7L)).thenReturn(redeemed);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-reuse");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_ALREADY_REDEEMED");
        verify(mapper, never()).restartTrial(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void retiredEarlyRedeemRejectsEvenWhenTheWalletWouldBeInsufficient() {
        TrialRow active = activeTrial();
        when(mapper.lockTrial(7L)).thenReturn(active);
        when(mapper.lockConversionProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "Trial", BigDecimal.TEN, 1, "P1"));
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(new BigDecimal("10"), BigDecimal.ZERO));

        ApiResult<Map<String, Object>> result = service.redeemEarly(7L, "h2-low-balance");

        assertThat(result.getCode()).isEqualTo(410);
        assertThat(result.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(any(), any(), any(), any());
        verify(mapper, never()).failTrial(anyLong(), anyLong(), any(), any(), any(), any(), anyString());
        verify(outbox, never()).publishUserEvent(eq("TRIAL"), eq("TRIAL-1"), eq("trial.charge_attempted"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
    }

    @Test
    void retiredEarlyRedeemRejectsEvenWhenACompletePurchaseWouldOtherwiseSucceed() {
        TrialRow active = activeTrial();
        when(mapper.lockTrial(7L)).thenReturn(active);
        when(mapper.lockConversionProduct("stellarbox-s1")).thenReturn(
                new AppTrialLifecycleMapper.ConversionProduct(9L, "stellarbox-s1", "Trial", "Entry",
                        new BigDecimal("1299"), 1, "P1", "DEVICE", "FINITE"));
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(new BigDecimal("2000"), BigDecimal.ZERO));
        when(mapper.settleWallet(eq(7L), any(), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO))).thenReturn(1);
        when(mapper.decrementProductStock(9L)).thenReturn(1);
        when(mapper.insertConversionOrder(eq(7L), anyString(), eq(9L), any(), any(), any())).thenReturn(1);
        when(mapper.insertConversionOrderItem(anyString(), eq(9L), eq("stellarbox-s1"), eq("Trial"), any()))
                .thenReturn(1);
        when(mapper.insertPurchasedDevice(eq(7L), anyString(), eq(9L), eq("stellarbox-s1"),
                eq("Entry"), eq("DEVICE"), anyString(), eq("Trial"), any(), any(), any())).thenReturn(1);
        when(mapper.deviceIdByInstanceNo(anyString())).thenReturn(77L);
        when(mapper.markRedeemed(eq(1L), eq(0L), eq(77L), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(1);

        ApiResult<Map<String, Object>> result = service.redeemEarly(7L, "h2-success");

        assertThat(result.getCode()).isEqualTo(410);
        assertThat(result.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertConversionOrderItem(anyString(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertPurchasedDevice(anyLong(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any());
        verify(mapper, never()).markRedeemed(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), anyString());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
        verify(outbox, never()).publishUserEvent(eq("TRIAL"), eq("TRIAL-1"), eq("trial.redeemed"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
    }

    @Test
    void retiredRedeemAndChargeRemainIdempotentlyRejectedWithoutPurchaseSideEffects() {
        ApiResult<Map<String, Object>> firstRedeem = service.redeemEarly(7L, "retired-redeem");
        ApiResult<Map<String, Object>> repeatedRedeem = service.redeemEarly(7L, "retired-redeem");
        ApiResult<Map<String, Object>> firstCharge = service.charge(7L, "retired-charge");
        ApiResult<Map<String, Object>> repeatedCharge = service.charge(7L, "retired-charge");

        assertThat(firstRedeem.getCode()).isEqualTo(410);
        assertThat(repeatedRedeem.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        assertThat(firstCharge.getCode()).isEqualTo(410);
        assertThat(repeatedCharge.getMessage()).isEqualTo("TRIAL_EXPLICIT_PURCHASE_REQUIRED");
        verify(mapper, never()).lockTrial(anyLong());
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(anyLong(), any(), any(), any());
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertConversionOrderItem(anyString(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertPurchasedDevice(anyLong(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any());
        verify(mapper, never()).markRedeemed(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void expiredActiveStateProjectionIsPureReadAndNeverAccruesBeyondConfiguredTrialDays() {
        LocalDateTime now = LocalDateTime.now();
        TrialRow expired = new TrialRow(1L, 7L, "TRIAL-1", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), "productCode=stellarbox-s1", now.minusDays(8), now.minusDays(5),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 0L);
        when(mapper.trial(7L)).thenReturn(expired);
        when(mapper.lockTrial(7L)).thenReturn(expired);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("state", "GRACE")
                .containsEntry("canStart", false)
                .containsEntry("shadowUsdt", new BigDecimal("120.000000"))
                .containsEntry("shadowNex", new BigDecimal("15.000000"))
                .containsKey("claimedAtEpochMs")
                .containsKey("expiresAtEpochMs")
                .containsKey("graceEndsAtEpochMs");
        verify(mapper, never()).enterGrace(anyLong(), anyLong(), any());
        verify(outbox, never()).publishUserEvent(eq("TRIAL"), eq("TRIAL-1"), eq("trial.grace_entered"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
        verify(audit, never()).record(any());
        assertThat(result.getData().get("config")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey("chargeFailRate")
                .containsEntry("trialOffsetCapUSD", "50");
    }

    @Test
    void dueSettlementCancelsServerSideWhenAutoChargeIsDisabled() {
        LocalDateTime now = LocalDateTime.now();
        TrialRow overdue = new TrialRow(1L, 7L, "TRIAL-DUE", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), "productCode=stellarbox-s1", now.minusDays(12), now.minusDays(9),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 3L);
        when(mapper.lockTrial(7L)).thenReturn(overdue);
        when(mapper.cancelTrial(eq(1L), eq(3L), eq("auto_end"), any(), any())).thenReturn(1);
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("graceDays", "7"),
                new PolicyRow("cooldownDays", "30"),
                new PolicyRow("autoChargeAtEnd", "false")));

        ApiResult<Map<String, Object>> result =
                service.settleDue(7L, "TRIAL-DUE", "h2-auto:TRIAL-DUE");

        assertThat(result.getCode()).isZero();
        verify(mapper).cancelTrial(eq(1L), eq(3L), eq("auto_end"), any(), any());
        verify(mapper, never()).settleWallet(any(), any(), any(), any());
        verify(outbox).publishUserEvent(eq("TRIAL"), eq("TRIAL-DUE"), eq("trial.cancelled"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
    }

    @Test
    void autoChargeEnabledStaleDueSettlementStillCancelsWithoutAnyAutomaticPurchase() {
        LocalDateTime now = LocalDateTime.now();
        TrialRow stale = new TrialRow(1L, 7L, "TRIAL-STALE", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), "productCode=stellarbox-s1", now.minusDays(40), now.minusDays(37),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 4L);
        when(mapper.lockTrial(7L)).thenReturn(stale);
        when(mapper.cancelTrial(eq(1L), eq(4L), eq("auto_settlement_window_expired"), any(), any()))
                .thenReturn(1);
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("graceDays", "7"),
                new PolicyRow("cooldownDays", "30"),
                new PolicyRow("autoChargeAtEnd", "true")));

        ApiResult<Map<String, Object>> result =
                service.settleDue(7L, "TRIAL-STALE", "h2-auto:TRIAL-STALE");

        assertThat(result.getCode()).isZero();
        verify(mapper).cancelTrial(eq(1L), eq(4L), eq("auto_settlement_window_expired"), any(), any());
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).settleWallet(any(), any(), any(), any());
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).insertConversionOrder(anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertConversionOrderItem(anyString(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertPurchasedDevice(anyLong(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any());
        verify(mapper, never()).markRedeemed(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), anyString());
        verify(outbox).publishUserEvent(eq("TRIAL"), eq("TRIAL-STALE"), eq("trial.cancelled"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
    }

    private TrialRow activeTrial() {
        LocalDateTime now = LocalDateTime.now();
        return new TrialRow(1L, 7L, "TRIAL-1", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), "productCode=stellarbox-s1", now.minusDays(1), now.plusDays(2),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 0L);
    }

    private TrialRow trialWithStatus(String status, LocalDateTime cooldownUntil, long version) {
        LocalDateTime now = LocalDateTime.now();
        boolean active = "ACTIVE".equals(status);
        return new TrialRow(1L, 7L, "TRIAL-LEGACY", status, null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), "productCode=stellarbox-s1", active ? now.minusDays(1) : now.minusDays(4),
                active ? now.plusDays(2) : now.minusDays(1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, cooldownUntil, version);
    }
}
