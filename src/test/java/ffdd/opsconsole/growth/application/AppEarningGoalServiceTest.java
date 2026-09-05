package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppEarningGoalMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppProductCatalogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppEarningGoalServiceTest {
    private final AppEarningGoalMapper mapper = mock(AppEarningGoalMapper.class);
    private final AppProductCatalogService catalog = mock(AppProductCatalogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
    private final AppEarningGoalService service = new AppEarningGoalService(mapper, catalog, idempotency, clock);

    @Test
    void listIsAccountScopedAndReturnsServerProgress() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.list(42L)).thenReturn(List.of(new AppEarningGoalMapper.GoalRow(
                7L, 42L, new BigDecimal("1000"), LocalDateTime.now().plusDays(30), false,
                null, LocalDateTime.now(), LocalDateTime.now())));
        when(mapper.lifetimeEarnings(42L)).thenReturn(new BigDecimal("250"));

        ApiResult<AppEarningGoalService.GoalListView> result = service.list(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().serverCanonical()).isTrue();
        assertThat(result.getData().lifetimeEarningsUsdt()).isEqualByComparingTo("250");
        assertThat(result.getData().goals()).singleElement().satisfies(goal -> {
            assertThat(goal.id()).isEqualTo(7L);
            assertThat(goal.progressPct()).isEqualByComparingTo("25");
        });
        verify(mapper).list(42L);
    }

    @Test
    void createRejectsInvalidDeadlineBeforeWriting() {
        when(mapper.activeUser(42L)).thenReturn(42L);

        ApiResult<?> result = service.create(42L, new BigDecimal("100"),
                LocalDateTime.of(2026, 8, 30, 23, 59), "goal-save-1");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("GOAL_DEADLINE_INVALID");
    }

    @Test
    void recommendationSelectsAnAvailableCatalogProductByRealDailyYield() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.lifetimeEarnings(42L)).thenReturn(new BigDecimal("0"));
        when(catalog.catalog(42L)).thenReturn(ApiResult.ok(Map.of(
                "source", "nx_product",
                "products", List.of(
                        Map.of("id", "slow", "name", "Slow", "available", true,
                                "dailyEarn", new BigDecimal("2"), "price", new BigDecimal("500")),
                        Map.of("id", "fast", "name", "Fast", "available", true,
                                "dailyEarn", new BigDecimal("10"), "price", new BigDecimal("2000"))))));

        ApiResult<AppEarningGoalService.RecommendationView> result = service.recommendation(
                42L, new BigDecimal("1000"), LocalDateTime.of(2026, 12, 9, 0, 0));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().productNo()).isEqualTo("fast");
        assertThat(result.getData().source()).isEqualTo("nx_product");
        assertThat(result.getData().serverCanonical()).isTrue();
    }

    @Test
    void recommendationUsesUtcAndRoundsAThirtyDayDeadlineUpToThirtyDays() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.lifetimeEarnings(42L)).thenReturn(BigDecimal.ZERO);
        when(catalog.catalog(42L)).thenReturn(ApiResult.ok(Map.of(
                "source", "nx_product",
                "products", List.of(Map.of("id", "daily", "name", "Daily", "available", true,
                        "dailyEarn", new BigDecimal("40"), "price", new BigDecimal("1000"))))));

        ApiResult<AppEarningGoalService.RecommendationView> result = service.recommendation(
                42L, new BigDecimal("1000"), LocalDateTime.of(2026, 9, 30, 0, 0));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().days()).isEqualTo(30);
        assertThat(result.getData().requiredDaily()).isEqualByComparingTo("33.333334");
    }

    @Test
    void recommendationIsIndependentOfTheJvmDefaultTimeZone() {
        ZoneId original = ZoneId.systemDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            when(mapper.activeUser(42L)).thenReturn(42L);
            when(mapper.lifetimeEarnings(42L)).thenReturn(BigDecimal.ZERO);
            when(catalog.catalog(42L)).thenReturn(ApiResult.ok(Map.of(
                    "source", "nx_product",
                    "products", List.of(Map.of("id", "daily", "name", "Daily", "available", true,
                            "dailyEarn", new BigDecimal("40"), "price", new BigDecimal("1000"))))));

            ApiResult<AppEarningGoalService.RecommendationView> result = service.recommendation(
                    42L, new BigDecimal("1000"), LocalDateTime.of(2026, 9, 29, 12, 0));

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().days()).isEqualTo(30);
        } finally {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(original));
        }
    }

    @Test
    void createReadsBackTheInsertedGoalRatherThanAnotherConcurrentLatestGoal() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(idempotency.execute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get());
        when(mapper.insert(any(AppEarningGoalMapper.GoalInsert.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AppEarningGoalMapper.GoalInsert.class).setId(17L);
            return 1;
        });
        AppEarningGoalMapper.GoalRow inserted = new AppEarningGoalMapper.GoalRow(17L, 42L,
                new BigDecimal("1000"), LocalDateTime.of(2026, 9, 30, 0, 0), false,
                null, LocalDateTime.of(2026, 8, 31, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0));
        when(mapper.findById(42L, 17L)).thenReturn(inserted);
        when(mapper.lifetimeEarnings(42L)).thenReturn(BigDecimal.ZERO);

        ApiResult<AppEarningGoalService.GoalView> result = service.create(
                42L, new BigDecimal("1000"), LocalDateTime.of(2026, 9, 30, 0, 0), "goal-save-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo(17L);
        verify(idempotency).execute(eq("APP:GOAL_CREATE:USER:42"), eq("goal-save-1"),
                org.mockito.ArgumentMatchers.anyString(), eq(ApiResult.class), any());
        verify(mapper).findById(42L, 17L);
        verify(mapper, never()).latest(42L);
    }

    @Test
    void sandboxCatalogIsNotReplacedByClientThresholds() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.lifetimeEarnings(42L)).thenReturn(BigDecimal.ZERO);
        when(catalog.catalog(42L)).thenReturn(ApiResult.ok(Map.of(
                "source", "mock", "sourceEnvironment", "SANDBOX", "runId", "run-1",
                "products", List.of(Map.of("id", "sandbox-sku", "name", "Sandbox SKU",
                        "available", true, "dailyEarn", new BigDecimal("1"), "price", new BigDecimal("99"))))));

        ApiResult<AppEarningGoalService.RecommendationView> result = service.recommendation(
                42L, new BigDecimal("100"), LocalDateTime.of(2027, 3, 19, 0, 0));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().productNo()).isEqualTo("sandbox-sku");
        assertThat(result.getData().sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(result.getData().runId()).isEqualTo("run-1");
    }

    @Test
    void recommendationFailsClosedWhenCanonicalCatalogCannotBeRead() {
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(catalog.catalog(42L)).thenReturn(ApiResult.fail(503, "PRODUCT_CATALOG_INVALID"));

        ApiResult<?> result = service.recommendation(
                42L, new BigDecimal("100"), LocalDateTime.of(2026, 9, 30, 0, 0));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("GOAL_CATALOG_UNAVAILABLE");
    }
}
