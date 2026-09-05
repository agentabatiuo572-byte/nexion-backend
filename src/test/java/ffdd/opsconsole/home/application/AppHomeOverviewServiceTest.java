package ffdd.opsconsole.home.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.ComputeTaskProofVerifier;
import ffdd.opsconsole.growth.application.GrowthPublicStatsService;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppHomeOverviewServiceTest {
    private final AppHomeOverviewMapper mapper = org.mockito.Mockito.mock(AppHomeOverviewMapper.class);
    private final ComputeTaskProofVerifier verifier = org.mockito.Mockito.mock(ComputeTaskProofVerifier.class);
    private final GrowthPublicStatsService publicStats = org.mockito.Mockito.mock(GrowthPublicStatsService.class);
    private final AppCanonicalBoundaryService purchaseEligibility =
            org.mockito.Mockito.mock(AppCanonicalBoundaryService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC);
    private final MockEnvironment environment = new MockEnvironment();
    private final AppHomeOverviewService service =
            new AppHomeOverviewService(mapper, verifier, publicStats, purchaseEligibility, clock, environment);

    @Test
    void sandboxReturnsCurrentRunProjectionWithoutReadingProductionFacts() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        environment.setActiveProfiles("test");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "home-run-20260819");
        environment.setProperty("NEXION_BUILD_CANDIDATE_ID", "a".repeat(64));
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(true));
        when(publicStats.overview()).thenReturn(ffdd.opsconsole.shared.api.ApiResult.ok(Map.of(
                "values", Map.of("fleetDevices", 28_432, "onlineRatePct", new BigDecimal("99.5")),
                "publishedDailyUsdPerDevice", new BigDecimal("0.06"))));
        when(mapper.sandboxActiveDevices(42L, "home-run-20260819")).thenReturn(2L);
        when(mapper.marketTasks()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketTaskRow("TK-IG", "IG", "Image generation", "/job",
                        new BigDecimal("0.045"), "SDXL Turbo", new BigDecimal("0.0001"), new BigDecimal("0.045")),
                new AppHomeOverviewMapper.MarketTaskRow("TK-IG-LEGACY", "IMAGE_GENERATION", "Image generation legacy", "/job",
                        new BigDecimal("0.040"), "SDXL", new BigDecimal("0.0001"), new BigDecimal("0.040"))));
        when(mapper.marketProducts()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketProductRow("stellarbox-s1", "StellarBox S1", "DEVICE", "S1",
                        new BigDecimal("1299"), new BigDecimal("1"), 1)));
        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertEquals("SANDBOX", result.getData().get("sourceEnvironment"));
        assertEquals("home-run-20260819", result.getData().get("runId"));
        assertEquals(true, result.getData().get("serverCanonical"));
        String serverCandidateId = String.valueOf(result.getData().get("serverCandidateId"));
        assertTrue(serverCandidateId.matches("[0-9a-f]{64}"));
        assertNotEquals("a".repeat(64), serverCandidateId,
                "candidate evidence must be computed from runtime classes, never trusted from environment input");
        assertEquals("SANDBOX_QUOTE_EXAMPLES", result.getData().get("earningsLedgerMode"));
        assertEquals(1, ((List<?>) result.getData().get("earningsLedger")).size());
        assertEquals(true, ((Map<?, ?>) ((List<?>) result.getData().get("earningsLedger")).get(0)).get("synthetic"));
        assertEquals(1, ((List<?>) ((Map<?, ?>) result.getData().get("marketBoard")).get("workloads")).size());
        assertEquals(28_290L, ((Map<?, ?>) result.getData().get("onGrid")).get("activeDevices"));
        assertEquals(1L, ((Map<?, ?>) result.getData().get("onGrid")).get("activeJobs"));
        assertNull(((Map<?, ?>) ((Map<?, ?>) result.getData().get("earnings")).get("all")).get("usdt"));
        verify(mapper).userEnvironment(42L);
        verify(mapper, org.mockito.Mockito.never()).earningsSummary(eq(42L), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sandboxAccountMismatchIsRejectedBeforeFactsUnavailable() {
        when(verifier.sourceEnvironment()).thenReturn("SANDBOX");
        environment.setActiveProfiles("test");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));

        var result = service.overview(42L);

        assertEquals(403, result.getCode());
        assertEquals("USER_ENVIRONMENT_MISMATCH", result.getMessage());
        verify(mapper).userEnvironment(42L);
        verify(mapper, org.mockito.Mockito.never()).earnings(eq(42L), any(), any(), any());
    }

    @Test
    void isolatedAcceptanceProfileFailsClosedEvenIfProofModeDefaultsToProduction() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        environment.setActiveProfiles("test");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(true));

        var result = service.overview(42L);

        assertEquals(503, result.getCode());
        assertEquals("APP_HOME_SANDBOX_RUN_ID_REQUIRED", result.getMessage());
        verify(mapper).userEnvironment(42L);
        verify(mapper, org.mockito.Mockito.never()).earnings(eq(42L), any(), any(), any());
    }

    @Test
    void reservedLegacyRunIdIsNeverAcceptedAsTheCurrentSandboxRun() {
        when(verifier.sourceEnvironment()).thenReturn("SANDBOX");
        environment.setActiveProfiles("test");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "LEGACY_UNSCOPED");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(true));

        var result = service.overview(42L);

        assertEquals(503, result.getCode());
        assertEquals("APP_HOME_SANDBOX_RUN_ID_REQUIRED", result.getMessage());
        verify(publicStats, org.mockito.Mockito.never()).overview();
    }

    @Test
    void rejectsAccountFromDifferentEnvironmentBeforeReadingFacts() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(true));

        assertEquals(403, service.overview(42L).getCode());
        verify(mapper, org.mockito.Mockito.never()).earnings(eq(42L), any(), any(), any());
    }

    @Test
    void productionAccountReadsOnlyProductionProjectionAndMayReturnEmptyFacts() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertEquals(true, result.getData().get("serverCanonical"));
        assertEquals("PRODUCTION", result.getData().get("sourceEnvironment"));
        assertEquals("", result.getData().get("runId"));
        assertEquals("SETTLED", result.getData().get("earningsLedgerMode"));
        verify(mapper).userEnvironment(42L);
        verify(mapper).earningsSummary(eq(42L), eq("PRODUCTION"), any(), any(), any(), any(), any(), any());
        verify(mapper, org.mockito.Mockito.never()).earnings(any(), any(), any(), any());
    }

    @Test
    void productionEarningsExposeRealTodayToSameTimeYesterdayComparison() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        LocalDateTime todayStart = LocalDateTime.parse("2026-08-15T00:00:00");
        LocalDateTime now = LocalDateTime.parse("2026-08-15T08:00:00");
        LocalDateTime yesterdayStart = LocalDateTime.parse("2026-08-14T00:00:00");
        LocalDateTime yesterdaySameTime = LocalDateTime.parse("2026-08-14T08:00:00");
        when(mapper.earningsSummary(eq(42L), eq("PRODUCTION"), eq(todayStart), eq(yesterdayStart),
                eq(yesterdaySameTime), any(), any(), eq(now)))
                .thenReturn(new AppHomeOverviewMapper.EarningsSummaryRow(
                        new BigDecimal("105.20"), BigDecimal.ZERO, 5L,
                        new BigDecimal("100.00"), BigDecimal.ZERO, 4L,
                        null, null, 0L, null, null, 0L, null, null, 0L));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        var earnings = (Map<?, ?>) result.getData().get("earnings");
        assertEquals(new BigDecimal("5.20"), earnings.get("todayVsYesterdayPct"));
        verify(mapper).earningsSummary(eq(42L), eq("PRODUCTION"), eq(todayStart), eq(yesterdayStart),
                eq(yesterdaySameTime), any(), any(), eq(now));
    }

    @Test
    void productionTodayRollsOverAtShanghaiMidnightInsteadOfJvmTimezone() {
        Clock midnightClock = Clock.fixed(
                Instant.parse("2026-08-14T16:00:01Z"), ZoneOffset.UTC);
        AppHomeOverviewService midnightService = new AppHomeOverviewService(
                mapper, verifier, publicStats, purchaseEligibility, midnightClock, environment);
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));

        var result = midnightService.overview(42L);

        assertEquals(0, result.getCode());
        verify(mapper).earningsSummary(eq(42L), eq("PRODUCTION"),
                eq(LocalDateTime.parse("2026-08-15T00:00:00")),
                eq(LocalDateTime.parse("2026-08-14T00:00:00")),
                eq(LocalDateTime.parse("2026-08-14T00:00:01")), any(), any(),
                eq(LocalDateTime.parse("2026-08-15T00:00:01")));
    }

    @Test
    void productionGridReturnsDatabaseBackedClientNameAndCity() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.onGridClients(false)).thenReturn(List.of(
                new AppHomeOverviewMapper.OnGridClientRow(
                        "client_abc123", "NexGrid Mobile Network", "Mobile NPU", "Global", 7L)));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        var clients = (List<?>) ((Map<?, ?>) result.getData().get("onGrid")).get("clients");
        var client = (Map<?, ?>) clients.get(0);
        assertEquals("NexGrid Mobile Network", client.get("name"));
        assertEquals("Global", client.get("city"));
        assertEquals(
                "server:nx_compute_receipt,nx_compute_task,nx_user_device,nx_compute_datacenter,nx_product,nx_growth_promo_banner",
                result.getData().get("source"));
    }

    @Test
    void productionMarketBoardReturnsDatabaseBackedPriceHistoryAndTwentyFourHourDelta() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.marketTasks()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketTaskRow("TK-IG", "IG", "Image generation", "/job",
                        new BigDecimal("0.055"), "SDXL Turbo", new BigDecimal("0.0001"), new BigDecimal("0.055"))));
        when(mapper.marketTaskPriceHistory()).thenReturn(List.of(
                new AppHomeOverviewMapper.TaskPriceHistoryRow("TK-IG", new BigDecimal("0.050"),
                        LocalDateTime.parse("2026-08-14T08:05:00")),
                new AppHomeOverviewMapper.TaskPriceHistoryRow("TK-IG", new BigDecimal("0.051"),
                        LocalDateTime.parse("2026-08-15T07:05:00")),
                new AppHomeOverviewMapper.TaskPriceHistoryRow("TK-IG", new BigDecimal("0.052"),
                        LocalDateTime.parse("2026-08-15T07:20:00")),
                new AppHomeOverviewMapper.TaskPriceHistoryRow("TK-IG", new BigDecimal("0.053"),
                        LocalDateTime.parse("2026-08-15T07:40:00")),
                new AppHomeOverviewMapper.TaskPriceHistoryRow("TK-IG", new BigDecimal("0.055"),
                        LocalDateTime.parse("2026-08-15T08:00:00"))));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        var workloads = (List<?>) ((Map<?, ?>) result.getData().get("marketBoard")).get("workloads");
        var workload = (Map<?, ?>) workloads.get(0);
        assertEquals(new BigDecimal("10.00"), workload.get("deltaPct"));
        assertEquals(List.of(
                new BigDecimal("0.051"),
                new BigDecimal("0.052"),
                new BigDecimal("0.053"),
                new BigDecimal("0.055")), workload.get("sparkline"));
    }

    @Test
    void productionHomeBuildsDoTheMathFromOwnedDeviceAndNextPurchasableCatalogProduct() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.highestActiveDevice(42L, false)).thenReturn(
                new AppHomeOverviewMapper.OwnedDeviceRow(
                        "Your phone", "phone", "TIER-3", "MOBILE", new BigDecimal("0.060000")));
        when(mapper.marketProducts()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketProductRow("stellarbox-s1", "NexionBox S1", "SERVER", "Entry",
                        new BigDecimal("1299.00"), new BigDecimal("1.000000"), 0),
                new AppHomeOverviewMapper.MarketProductRow("stellarbox-pro", "StellarBox Pro", "SERVER", "Pro",
                        new BigDecimal("1199.00"), new BigDecimal("13.000000"), 3),
                new AppHomeOverviewMapper.MarketProductRow("stellarrack-p1", "StellarRack P1", "DEVICE", "Flagship",
                        new BigDecimal("4499.00"), new BigDecimal("45.000000"), 2)));
        when(purchaseEligibility.purchaseEligibilityBatch(
                42L, List.of("stellarbox-pro", "stellarrack-p1")))
                .thenReturn(eligibilityBatch(decision("stellarbox-pro", true)));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        var calculator = (Map<?, ?>) result.getData().get("doTheMath");
        var base = (Map<?, ?>) calculator.get("base");
        var target = (Map<?, ?>) calculator.get("target");
        assertEquals("OWNED_DEVICE_TO_NEXT_CATALOG_PRODUCT", calculator.get("basis"));
        assertEquals("Your phone", base.get("name"));
        assertEquals("phone", base.get("kind"));
        assertEquals(new BigDecimal("0.060000"), base.get("dailyUsdt"));
        assertEquals("stellarbox-pro", target.get("productNo"));
        assertEquals("StellarBox Pro", target.get("name"));
        assertEquals(new BigDecimal("13.000000"), target.get("dailyUsdt"));
        assertEquals(new BigDecimal("1199.00"), target.get("priceUsdt"));
        assertEquals(217L, calculator.get("multiplier"));
        assertEquals(92L, calculator.get("paybackDays"));
    }

    @Test
    void productionHomeSkipsUnreleasedUpgradeAndUsesNextImmediatelyPurchasableProduct() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.highestActiveDevice(42L, false)).thenReturn(
                new AppHomeOverviewMapper.OwnedDeviceRow(
                        "Your phone", "phone", "TIER-3", "MOBILE", new BigDecimal("0.060000")));
        when(mapper.marketProducts()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketProductRow("stellarbox-pro", "StellarBox Pro", "SERVER", "Pro",
                        new BigDecimal("1199.00"), new BigDecimal("13.000000"), 3),
                new AppHomeOverviewMapper.MarketProductRow("stellarrack-p1", "StellarRack P1", "DEVICE", "Flagship",
                        new BigDecimal("4499.00"), new BigDecimal("45.000000"), 2)));
        when(purchaseEligibility.purchaseEligibilityBatch(
                42L, List.of("stellarbox-pro", "stellarrack-p1")))
                .thenReturn(eligibilityBatch(
                        decision("stellarbox-pro", false),
                        decision("stellarrack-p1", true)));

        var result = service.overview(42L);

        var calculator = (Map<?, ?>) result.getData().get("doTheMath");
        var target = (Map<?, ?>) calculator.get("target");
        assertEquals("stellarrack-p1", target.get("productNo"));
    }

    @Test
    void productionHomeHidesDoTheMathWhenNoHigherProductIsImmediatelyPurchasable() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.highestActiveDevice(42L, false)).thenReturn(
                new AppHomeOverviewMapper.OwnedDeviceRow(
                        "StellarBox Pro", "stellarbox-pro", "Pro", "DEVICE", new BigDecimal("13.000000")));
        when(mapper.marketProducts()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketProductRow("stellarrack-p1", "StellarRack P1", "DEVICE", "Flagship",
                        new BigDecimal("4499.00"), new BigDecimal("45.000000"), 2)));
        when(purchaseEligibility.purchaseEligibilityBatch(42L, List.of("stellarrack-p1")))
                .thenReturn(eligibilityBatch(decision("stellarrack-p1", false)));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertNull(result.getData().get("doTheMath"));
    }

    @Test
    void productionHomeFailsClosedWhenEligibilityCannotBeVerified() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.highestActiveDevice(42L, false)).thenReturn(
                new AppHomeOverviewMapper.OwnedDeviceRow(
                        "StellarBox Pro", "stellarbox-pro", "Pro", "DEVICE", new BigDecimal("13.000000")));
        when(mapper.marketProducts()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketProductRow("stellarrack-p1", "StellarRack P1", "DEVICE", "Flagship",
                        new BigDecimal("4499.00"), new BigDecimal("45.000000"), 2)));
        when(purchaseEligibility.purchaseEligibilityBatch(42L, List.of("stellarrack-p1")))
                .thenReturn(ApiResult.fail(503, "PURCHASE_ELIGIBILITY_UNAVAILABLE"));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertNull(result.getData().get("doTheMath"));
    }

    @Test
    void productionHomeFailsClosedWhenEligibilityCheckThrows() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.highestActiveDevice(42L, false)).thenReturn(
                new AppHomeOverviewMapper.OwnedDeviceRow(
                        "StellarBox Pro", "stellarbox-pro", "Pro", "DEVICE", new BigDecimal("13.000000")));
        when(mapper.marketProducts()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketProductRow("stellarrack-p1", "StellarRack P1", "DEVICE", "Flagship",
                        new BigDecimal("4499.00"), new BigDecimal("45.000000"), 2)));
        when(purchaseEligibility.purchaseEligibilityBatch(42L, List.of("stellarrack-p1")))
                .thenThrow(new IllegalStateException("eligibility dependency unavailable"));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertNull(result.getData().get("doTheMath"));
    }

    @Test
    void productionHomeRejectsEligibilityResponseForAnotherProduct() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.highestActiveDevice(42L, false)).thenReturn(
                new AppHomeOverviewMapper.OwnedDeviceRow(
                        "StellarBox Pro", "stellarbox-pro", "Pro", "DEVICE", new BigDecimal("13.000000")));
        when(mapper.marketProducts()).thenReturn(List.of(
                new AppHomeOverviewMapper.MarketProductRow("stellarrack-p1", "StellarRack P1", "DEVICE", "Flagship",
                        new BigDecimal("4499.00"), new BigDecimal("45.000000"), 2)));
        when(purchaseEligibility.purchaseEligibilityBatch(42L, List.of("stellarrack-p1")))
                .thenReturn(eligibilityBatch(decision("another-product", true)));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertNull(result.getData().get("doTheMath"));
    }

    @Test
    void developmentAccountReadsCanonicalPcBackedProjectionWithoutRunId() {
        when(verifier.sourceEnvironment()).thenReturn("SANDBOX");
        environment.setActiveProfiles("dev");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertEquals("PRODUCTION", result.getData().get("sourceEnvironment"));
        assertEquals("", result.getData().get("runId"));
        assertEquals("SETTLED", result.getData().get("earningsLedgerMode"));
        verify(mapper).earningsSummary(eq(42L), eq("PRODUCTION"), any(), any(), any(), any(), any(), any());
        verify(mapper, org.mockito.Mockito.never()).earnings(any(), any(), any(), any());
        verify(publicStats, org.mockito.Mockito.never()).overview();
    }

    @Test
    void unknownOrMixedProfileCannotBePresentedAsProductionHomeFacts() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        environment.setActiveProfiles("prod", "test");

        var result = service.overview(42L);

        assertEquals(503, result.getCode());
        assertEquals("APP_HOME_PROFILE_INVALID", result.getMessage());
        verify(mapper, org.mockito.Mockito.never()).userEnvironment(42L);
    }

    private AppCanonicalBoundaryService.PurchaseEligibilityDecision decision(
            String productNo, boolean eligible) {
        return new AppCanonicalBoundaryService.PurchaseEligibilityDecision(
                productNo, eligible, eligible ? "ELIGIBLE" : "PRODUCT_NOT_RELEASED");
    }

    private ApiResult<Map<String, AppCanonicalBoundaryService.PurchaseEligibilityDecision>> eligibilityBatch(
            AppCanonicalBoundaryService.PurchaseEligibilityDecision... decisions) {
        Map<String, AppCanonicalBoundaryService.PurchaseEligibilityDecision> data = new LinkedHashMap<>();
        for (var decision : decisions) data.put(decision.productNo(), decision);
        return ApiResult.ok(data);
    }
}
