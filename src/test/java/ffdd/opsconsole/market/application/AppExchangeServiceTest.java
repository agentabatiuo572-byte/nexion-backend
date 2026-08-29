package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;

class AppExchangeServiceTest {
    private final AppExchangeMapper mapper = mock(AppExchangeMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final G2ExchangeFeeAllocationService feeAllocationService = mock(G2ExchangeFeeAllocationService.class);
    private final Environment environment = mock(Environment.class);
    private AppExchangeService service;

    @BeforeEach
    void setUp() {
        service = new AppExchangeService(mapper, config, idempotency, outbox, audit,
                feeAllocationService, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC), environment,
                java.util.Optional.empty());
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.userSandbox(7L)).thenReturn(0);
        when(mapper.lockExchangeExecutionMutex()).thenReturn("G2_EXCHANGE_EXECUTION");
        when(mapper.currentPrice()).thenReturn(BigDecimal.ONE);
        when(mapper.lockActiveUserNo(7L)).thenReturn("U00000007");
        when(mapper.lockWalletGate(7L)).thenReturn(
                new AppExchangeMapper.WalletGateRow(new BigDecimal("500"), new BigDecimal("500"), "SG"));
        when(mapper.geoBlocked("SG")).thenReturn(0);
        when(mapper.userTodayUsdt(7L)).thenReturn(BigDecimal.ZERO);
        when(mapper.platformTodayUsdt()).thenReturn(BigDecimal.ZERO);
        when(mapper.insertOrder(any())).thenReturn(1);
        when(mapper.applyWalletDelta(eq(7L), any(), any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(7L)).thenReturn(new AppExchangeMapper.UserAttribution("P1", 3, "2026-W30"));
        when(feeAllocationService.allocate(anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    BigDecimal totalFee = invocation.getArgument(1, BigDecimal.class).setScale(6);
                    BigDecimal burnPool = totalFee.multiply(new BigDecimal("0.30")).setScale(6);
                    return new G2ExchangeFeeAllocationService.Allocation(
                            totalFee, burnPool, totalFee.subtract(burnPool));
                });
    }

    @Test
    void completesSwapUsingBalanceCapAndRegionControlsOnly() {
        var result = service.swap(7L, "idem-g2-direct",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false));

        assertThat(result.getCode()).isZero();
        assertThat(((java.util.Map<?, ?>) result.getData().get("order")).get("status"))
                .isEqualTo("COMPLETED");
        InOrder capOrder = inOrder(mapper);
        capOrder.verify(mapper).lockExchangeExecutionMutex();
        capOrder.verify(mapper).platformTodayUsdt();
        capOrder.verify(mapper).applyWalletDelta(eq(7L), any(), any());
    }

    @Test
    void exposesDirectionSpecificMinimumsFromCanonicalConfiguration() {
        when(config.activeValue("wallet.exchange.min_usdt")).thenReturn(Optional.of("3"));
        when(config.activeValue("wallet.exchange.min_nex")).thenReturn(Optional.of("42"));

        var caps = service.caps().getData();

        assertThat(caps).containsEntry("minUsdt", new BigDecimal("3"));
        assertThat(caps).containsEntry("minNex", new BigDecimal("42"));
    }

    @Test
    void rejectsSwapBelowConfiguredDirectionMinimumBeforeWalletMutation() {
        when(config.activeValue("wallet.exchange.min_usdt")).thenReturn(Optional.of("3"));

        assertThatThrownBy(() -> service.swap(7L, "idem-g2-minimum",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("2.99"), false)))
                .hasMessageContaining("EXCHANGE_AMOUNT_BELOW_MINIMUM");
    }

    @Test
    void servesRunScopedSandboxMarketWithoutReadingProductionTables() {
        for (String profile : List.of("test")) {
            when(environment.getActiveProfiles()).thenReturn(new String[]{profile});
            when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID")).thenReturn("exchange-read-model-run");

            assertThat(service.caps().getData())
                    .containsEntry("serverCanonical", true)
                    .containsEntry("sourceEnvironment", "SANDBOX")
                    .containsEntry("runId", "exchange-read-model-run")
                    .containsEntry("source", "mock")
                    .containsEntry("swapEnabled", true);
            assertThat(service.market().getData())
                    .containsEntry("source", "mock")
                    .containsEntry("sourceEnvironment", "SANDBOX")
                    .containsEntry("runId", "exchange-read-model-run");
            assertThat(service.externalMarket().getData())
                    .containsEntry("serverCanonical", true)
                    .containsEntry("source", "mock")
                    .containsEntry("sourceEnvironment", "SANDBOX")
                    .containsEntry("runId", "exchange-read-model-run")
                    .containsEntry("availability", "AVAILABLE");
        }
        verifyNoInteractions(mapper, config);
    }

    @Test
    void developmentMarketReadsCanonicalG3ConfigurationWithoutRunId() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(config.activeValue("wallet.nex_market.weekly_curve")).thenReturn(Optional.of("""
                [{"dayIndex":0,"targetPrice":0.10,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":1,"targetPrice":0.11,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":2,"targetPrice":0.12,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":3,"targetPrice":0.13,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":4,"targetPrice":0.14,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":5,"targetPrice":0.15,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":6,"targetPrice":0.16,"pumpProbability":0.1,"volatilityPct":1}]
                """));

        assertThat(service.caps().getData())
                .containsEntry("source", "G2/G3 server configuration")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        assertThat(service.market().getData())
                .containsEntry("source", "G3 weekly_curve + nx_price_index 24h history")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
    }

    @Test
    void publicMarketFallsBackToPcConfiguredPriceWhenFiveMinuteIndexWindowIsEmpty() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(mapper.currentPrice()).thenReturn(null);
        when(config.activeValue("wallet.exchange.nex_usdt_price")).thenReturn(Optional.of("0.119"));
        when(config.activeValue("wallet.nex_market.weekly_curve")).thenReturn(Optional.of("""
                [{"dayIndex":0,"targetPrice":0.10,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":1,"targetPrice":0.11,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":2,"targetPrice":0.12,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":3,"targetPrice":0.13,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":4,"targetPrice":0.14,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":5,"targetPrice":0.15,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":6,"targetPrice":0.16,"pumpProbability":0.1,"volatilityPct":1}]
                """));

        assertThat(service.caps().getData()).containsEntry("currentPrice", new BigDecimal("0.119"));
        assertThat(service.market().getData()).containsEntry("currentPrice", new BigDecimal("0.119"));
    }

    @Test
    void productionExternalMarketFailsClosedWhenNoServerRowsExist() {
        when(mapper.latestExternalMarketPoints()).thenReturn(List.of());

        assertThat(service.externalMarket().getData())
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "nx_price_index:external-market")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("availability", "UNAVAILABLE")
                .containsEntry("quotes", List.of());
    }

    @Test
    void productionExternalMarketMapsOnlyAllowlistedServerIndexRows() {
        when(mapper.latestExternalMarketPoints()).thenReturn(List.of(
                new AppExchangeMapper.ExternalMarketPoint(
                        "EXT_RNDR_USDT", "Render / USDT", new BigDecimal("7.84"),
                        new BigDecimal("3.2"), new BigDecimal("184500000"),
                        "[7.5,7.6,7.55,7.7,7.84]", java.time.LocalDateTime.parse("2026-07-22T09:55:00"))));

        var market = service.externalMarket().getData();

        assertThat(market)
                .containsEntry("availability", "UNAVAILABLE")
                .containsEntry("quotes", List.of());
    }

    @Test
    void productionExternalMarketIsAvailableOnlyForTheCompleteAllowlist() {
        when(mapper.latestExternalMarketPoints()).thenReturn(List.of(
                externalPoint("EXT_RNDR_USDT", "7.84"),
                externalPoint("EXT_TAO_USDT", "342.10"),
                externalPoint("EXT_AKT_USDT", "3.24"),
                externalPoint("EXT_FIL_USDT", "5.18"),
                externalPoint("EXT_GRT_USDT", "0.243")));

        var market = service.externalMarket().getData();

        assertThat(market)
                .containsEntry("availability", "AVAILABLE")
                .containsEntry("sampledAt", java.time.LocalDateTime.parse("2026-07-22T09:55:00"));
        assertThat((List<?>) market.get("quotes")).extracting(value -> String.valueOf(((java.util.Map<?, ?>) value).get("symbol")))
                .containsExactlyInAnyOrderElementsOf(List.of("RNDR", "TAO", "AKT", "FIL", "GRT"));
    }

    @Test
    void productionExternalMarketRejectsStaleAndFutureRowsDefensively() {
        when(mapper.latestExternalMarketPoints()).thenReturn(List.of(
                externalPointAt("2026-07-22T09:54:59"),
                externalPointAt("2026-07-22T10:01:01")));

        assertThat(service.externalMarket().getData())
                .containsEntry("availability", "UNAVAILABLE")
                .containsEntry("quotes", List.of());
    }

    private static AppExchangeMapper.ExternalMarketPoint externalPointAt(String sampledAt) {
        return new AppExchangeMapper.ExternalMarketPoint(
                "EXT_RNDR_USDT", "Render / USDT", new BigDecimal("7.84"),
                new BigDecimal("3.2"), new BigDecimal("184500000"),
                "[7.5,7.6,7.55,7.7,7.84]", java.time.LocalDateTime.parse(sampledAt));
    }

    private static AppExchangeMapper.ExternalMarketPoint externalPoint(String metricCode, String price) {
        return new AppExchangeMapper.ExternalMarketPoint(
                metricCode, metricCode, new BigDecimal(price),
                new BigDecimal("3.2"), new BigDecimal("184500000"),
                "[1,2,3,4,5]", java.time.LocalDateTime.parse("2026-07-22T09:55:00"));
    }

    @Test
    void productionNexHistoryRejectsFutureRowsDefensively() {
        when(config.activeValue("wallet.nex_market.weekly_curve")).thenReturn(Optional.of("""
                [{"dayIndex":0,"targetPrice":0.10,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":1,"targetPrice":0.11,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":2,"targetPrice":0.12,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":3,"targetPrice":0.13,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":4,"targetPrice":0.14,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":5,"targetPrice":0.15,"pumpProbability":0.1,"volatilityPct":1},
                 {"dayIndex":6,"targetPrice":0.16,"pumpProbability":0.1,"volatilityPct":1}]
                """));
        when(mapper.recentMarketPoints()).thenReturn(List.of(
                new AppExchangeMapper.MarketPoint(new BigDecimal("0.12"), java.time.LocalDateTime.parse("2026-07-22T09:55:00")),
                new AppExchangeMapper.MarketPoint(new BigDecimal("99"), java.time.LocalDateTime.parse("2026-07-22T10:01:01"))));

        var history = (List<?>) service.market().getData().get("history24h");

        assertThat(history).singleElement().satisfies(value -> {
            var point = (java.util.Map<?, ?>) value;
            assertThat(point.get("price")).isEqualTo(new BigDecimal("0.12"));
        });
    }

    @Test
    void sandboxMutationsStillFailBeforeReadingProductionWallets() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});

        assertThatThrownBy(() -> service.swap(7L, "idem-sandbox-isolated",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false)))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(503))
                .hasMessageContaining("EXCHANGE_SANDBOX_ISOLATED_TABLE_UNAVAILABLE");
    }

    @Test
    void rejectsSandboxUserEvenWhenProductionProfileIsActive() {
        when(mapper.userSandbox(7L)).thenReturn(1);

        assertThatThrownBy(() -> service.swap(7L, "idem-g2-sandbox-user",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false)))
                .hasMessageContaining("EXCHANGE_PRODUCTION_USER_REQUIRED");
    }
}
