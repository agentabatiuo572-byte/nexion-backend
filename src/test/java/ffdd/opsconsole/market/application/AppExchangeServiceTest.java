package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;

class AppExchangeServiceTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "NEXION_SNAPSHOT_RACE_IT", matches = "true")
    void outerTransactionSeesUsageCommittedWhileWaitingForExecutionMutex() throws Exception {
        // Only this UUID-named fixture is written; all business mappers remain mocked.
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                ffdd.opsconsole.shared.idempotency.IsolatedMySqlSnapshotFixture.isolatedUrl(),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv().getOrDefault("NEXION_TEST_DB_PASSWORD", ""));
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        String table = "nx_audit_g2_snapshot_" + java.util.UUID.randomUUID().toString().replace("-", "");
        assertThat(table).matches("nx_audit_g2_snapshot_[a-f0-9]{32}");
        jdbc.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, amount DECIMAL(20,6) NOT NULL) ENGINE=InnoDB");
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            jdbc.update("INSERT INTO " + table + " VALUES (1,0)");
            var beforeLock = new java.util.concurrent.CountDownLatch(1);
            when(mapper.userSandbox(7L)).thenAnswer(invocation -> {
                assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM " + table, BigDecimal.class))
                        .isEqualByComparingTo(BigDecimal.ZERO);
                return 0;
            });
            when(mapper.lockExchangeExecutionMutex()).thenAnswer(invocation -> {
                beforeLock.countDown();
                jdbc.queryForObject("SELECT id FROM " + table + " WHERE id=1 FOR UPDATE", Integer.class);
                return "G2_EXCHANGE_EXECUTION";
            });
            when(mapper.platformTodayUsdt()).thenAnswer(invocation ->
                    jdbc.queryForObject("SELECT SUM(amount) FROM " + table, BigDecimal.class));
            when(config.activeValue("wallet.exchange.platform_daily_cap_usdt")).thenReturn(Optional.of("20"));
            var proxyFactory = new org.springframework.aop.framework.ProxyFactory(service);
            proxyFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var transactionalService = (AppExchangeService) proxyFactory.getProxy();
            try (var competing = dataSource.getConnection()) {
                competing.setAutoCommit(false);
                try (var statement = competing.createStatement()) {
                    statement.executeQuery("SELECT id FROM " + table + " WHERE id=1 FOR UPDATE").close();
                    var pending = worker.submit(() -> transactionalService.swap(7L, "isolated-cap-race",
                            new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false)));
                    assertThat(beforeLock.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    statement.executeUpdate("INSERT INTO " + table + " VALUES (2,20)");
                    competing.commit();
                    var result = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(((java.util.Map<?, ?>) result.getData().get("order")).get("status"))
                            .isEqualTo("PLATFORM_CAP");
                    verify(mapper, never()).applyWalletDelta(eq(7L), any(), any());
                }
            }
        } finally {
            worker.shutdownNow();
            try {
                assertThat(worker.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                jdbc.execute("DROP TABLE " + table);
            }
        }
    }

    @Test
    void swapReplaysOriginalReceiptAfter24HoursWithoutAnotherWalletMutationOrFee() {
        var durable = new ffdd.opsconsole.shared.idempotency.ExpiredReceiptFixture();
        var actual = new AppExchangeService(mapper, config, durable.service, outbox, audit,
                feeAllocationService, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC), environment,
                java.util.Optional.empty());
        var request = new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false);
        var first = actual.swap(7L, "late-exchange-retry", request);
        durable.advanceBeyondLease();
        when(mapper.currentPrice()).thenReturn(new BigDecimal("2"));
        var replay = actual.swap(7L, "late-exchange-retry", request);
        assertThat(replay.getCode()).isZero();
        Object exchangeNo = ((java.util.Map<?, ?>) first.getData().get("order")).get("exchangeNo");
        assertThat(exchangeNo).isNotNull();
        assertThat(((java.util.Map<?, ?>) replay.getData().get("order")).get("exchangeNo"))
                .isEqualTo(exchangeNo);
        verify(mapper, org.mockito.Mockito.times(1)).applyWalletDelta(eq(7L), any(), any());
        verify(mapper, org.mockito.Mockito.times(1)).insertOrder(any());
        verify(mapper, org.mockito.Mockito.times(2)).insertLedger(any());
        verify(feeAllocationService, org.mockito.Mockito.times(1)).allocate(anyString(), any(), any());
        verify(durable.records, never()).resetExpiredById(any(), anyString(), any());
        assertThatThrownBy(() -> actual.swap(7L, "late-exchange-retry",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("21"), false)))
                .hasMessageContaining("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        verify(mapper, org.mockito.Mockito.times(1)).applyWalletDelta(eq(7L), any(), any());
        verify(mapper, org.mockito.Mockito.times(1)).insertOrder(any());
        verify(feeAllocationService, org.mockito.Mockito.times(1)).allocate(anyString(), any(), any());
    }

    @Test
    void historyPaginationPinsTheAccountHighWaterMarkAcrossConcurrentInserts() {
        var firstRow = new AppExchangeMapper.ExchangeRow("EX-HISTORY-0001", "USDT", "NEX",
                new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ONE, "COMPLETED",
                java.time.LocalDateTime.parse("2026-07-22T10:00:00"));
        var secondRow = new AppExchangeMapper.ExchangeRow("EX-HISTORY-0002", "USDT", "NEX",
                new BigDecimal("9"), new BigDecimal("9"), BigDecimal.ONE, "COMPLETED",
                java.time.LocalDateTime.parse("2026-07-22T10:00:00"));
        when(mapper.maxIssuedHistoryId(7L)).thenReturn(40L);
        when(mapper.countUserOrdersAt(7L, 40L)).thenReturn(2L);
        when(mapper.userOrdersAt(7L, 0L, 1, 40L)).thenReturn(List.of(firstRow));
        when(mapper.userOrdersAt(7L, 1L, 1, 40L)).thenReturn(List.of(secondRow));

        var first = service.state(7L, 1, 1, null).getData();
        // A later order has id=41. The retained boundary must still select only ids <=40.
        when(mapper.maxIssuedHistoryId(7L)).thenReturn(41L);
        var second = service.state(7L, 2, 1, "40").getData();

        assertThat(first.get("ordersPage")).isEqualTo(Map.of(
                "total", 2L, "pageNum", 1, "pageSize", 1, "snapshotId", "40"));
        assertThat(second.get("ordersPage")).isEqualTo(Map.of(
                "total", 2L, "pageNum", 2, "pageSize", 1, "snapshotId", "40"));
        assertThat(((List<?>) first.get("orders"))).extracting(row -> ((AppExchangeMapper.ExchangeRow) row).exchangeNo())
                .containsExactly("EX-HISTORY-0001");
        assertThat(((List<?>) second.get("orders"))).extracting(row -> ((AppExchangeMapper.ExchangeRow) row).exchangeNo())
                .containsExactly("EX-HISTORY-0002");
        verify(mapper, org.mockito.Mockito.times(2)).countUserOrdersAt(7L, 40L);
        verify(mapper).userOrdersAt(7L, 1L, 1, 40L);
        verify(mapper, org.mockito.Mockito.times(2)).maxIssuedHistoryId(7L);
        verify(mapper, never()).maxIssuedHistoryId(8L);
        verify(mapper, never()).countUserOrdersAt(8L, 40L);
        verify(mapper, never()).userOrdersAt(8L, 1L, 1, 40L);
    }

    @Test
    void historyPaginationRejectsMalformedSnapshotIdBeforeReadingOrders() {
        assertThatThrownBy(() -> service.state(7L, 1, 20, "01"))
                .isInstanceOf(BizException.class)
                .hasMessage("HISTORY_SNAPSHOT_INVALID");
        verify(mapper, never()).countUserOrdersAt(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).userOrdersAt(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void historyPaginationRejectsUnissuedFutureIdBeforeReadingOrders() {
        when(mapper.maxIssuedHistoryId(7L)).thenReturn(40L);
        assertThatThrownBy(() -> service.state(7L, 1, 20, "41"))
                .isInstanceOf(BizException.class).hasMessage("HISTORY_SNAPSHOT_INVALID");
        verify(mapper, never()).countUserOrdersAt(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).userOrdersAt(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

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
                .when(idempotency).executeRetained(anyString(), anyString(), anyString(), any(), any());
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.userSandbox(7L)).thenReturn(0);
        when(mapper.lockExchangeExecutionMutex()).thenReturn("G2_EXCHANGE_EXECUTION");
        when(mapper.emergencyValue("killswitch.exchange")).thenReturn("enabled");
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
    void reservesSourceFundsBeforeCreatingACappedQueueOrder() {
        when(mapper.userTodayUsdt(7L)).thenReturn(new BigDecimal("990"));

        var result = service.swap(7L, "idem-g2-queue-reserve",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), true));

        assertThat(((java.util.Map<?, ?>) result.getData().get("order")).get("status"))
                .isEqualTo("QUEUED");
        InOrder order = inOrder(mapper);
        order.verify(mapper).applyWalletDelta(eq(7L),
                org.mockito.ArgumentMatchers.argThat(value -> value.compareTo(new BigDecimal("-20")) == 0),
                org.mockito.ArgumentMatchers.argThat(value -> value.compareTo(BigDecimal.ZERO) == 0));
        order.verify(mapper).insertOrder(any());
        verify(mapper).insertLedger(any(AppExchangeMapper.LedgerWrite.class));
    }

    @Test
    void rejectsCappedQueueOrderWhenSourceFundsCannotBeReserved() {
        when(mapper.userTodayUsdt(7L)).thenReturn(new BigDecimal("990"));
        when(mapper.applyWalletDelta(eq(7L),
                org.mockito.ArgumentMatchers.argThat(value -> value.compareTo(new BigDecimal("-20")) == 0),
                org.mockito.ArgumentMatchers.argThat(value -> value.compareTo(BigDecimal.ZERO) == 0))).thenReturn(0);

        assertThatThrownBy(() -> service.swap(7L, "idem-g2-queue-no-funds",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), true)))
                .hasMessageContaining("EXCHANGE_WALLET_INSUFFICIENT_OR_CONFLICT");
        verify(mapper, never()).insertOrder(any());
    }

    @Test
    void cancellingReservedQueueOrderRefundsTheHeldSourceAsset() {
        var durable = new ffdd.opsconsole.shared.idempotency.ExpiredReceiptFixture();
        var retainedService = new AppExchangeService(mapper, config, durable.service, outbox, audit,
                feeAllocationService, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC), environment,
                java.util.Optional.empty());
        when(mapper.lockOwnQueued(7L, "EX-RESERVED-1234")).thenReturn(
                new AppExchangeMapper.QueuedRow(7L, "EX-RESERVED-1234", "USDT", new BigDecimal("20")));
        when(mapper.sourceReservationExists("EX-RESERVED-1234")).thenReturn(1);
        when(mapper.cancelOwnQueued(7L, "EX-RESERVED-1234")).thenReturn(1);
        when(mapper.applyWalletDelta(7L, new BigDecimal("20"), BigDecimal.ZERO)).thenReturn(1);

        assertThat(retainedService.cancel(7L, "EX-RESERVED-1234", "idem-g2-cancel-refund").getCode()).isZero();
        durable.advanceBeyondLease();
        assertThat(retainedService.cancel(7L, "EX-RESERVED-1234", "idem-g2-cancel-refund").getCode()).isZero();

        verify(mapper).applyWalletDelta(7L, new BigDecimal("20"), BigDecimal.ZERO);
        verify(mapper).insertLedger(any(AppExchangeMapper.LedgerWrite.class));
        verify(mapper).cancelOwnQueued(7L, "EX-RESERVED-1234");
        verify(durable.records, never()).resetExpiredById(any(), anyString(), any());
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
                .containsEntry("source", "G3 weekly_curve + nx_price_index sampled history")
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
        when(mapper.marketHistoryPoints()).thenReturn(List.of(
                new AppExchangeMapper.MarketPoint(new BigDecimal("0.12"), java.time.LocalDateTime.parse("2026-07-22T09:55:00")),
                new AppExchangeMapper.MarketPoint(new BigDecimal("99"), java.time.LocalDateTime.parse("2026-07-22T10:01:01"))));

        var history = (List<?>) service.market().getData().get("history24h");

        assertThat(history).singleElement().satisfies(value -> {
            var point = (java.util.Map<?, ?>) value;
            assertThat(point.get("price")).isEqualTo(new BigDecimal("0.12"));
            assertThat(point.get("sampledAtEpochMs")).isEqualTo(Instant.parse("2026-07-22T01:55:00Z").toEpochMilli());
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
