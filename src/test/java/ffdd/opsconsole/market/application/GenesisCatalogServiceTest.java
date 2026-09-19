package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.CatalogState;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.SeriesBootstrapRow;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.TierRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GenesisCatalogServiceTest {
    private final GenesisCatalogMapper mapper = mock(GenesisCatalogMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final GenesisCatalogService service = new GenesisCatalogService(mapper,
            idempotency, audit,
            Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void updateTierRejectsBoundaryThatWouldInvertTheNextRangeBeforeAnyWrite() {
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "closed", 2L,
                "default", "seed", 3L));
        when(mapper.activeTiers()).thenReturn(List.of(
                new TierRow("t1", 0, 100, new BigDecimal("10")),
                new TierRow("t2", 100, 200, new BigDecimal("20"))));
        when(mapper.soldCount()).thenReturn(0L);

        assertThatThrownBy(() -> service.updateTierOnce("t1",
                new GenesisCatalogService.TierRequest(250, new BigDecimal("11"), 7L,
                        "reject inverted neighbor range", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(422);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_TIER_CROSSES_NEXT_RANGE");
                });

        verify(mapper, never()).updateTier(anyString(), anyInt(), any(BigDecimal.class));
        verify(mapper, never()).updateTierFrom(anyString(), anyInt());
        verify(mapper, never()).advanceTierVersion(anyLong(), anyLong());
    }

    @Test
    void publicStateFailsClosedWhenConfiguredOpenButNoActiveSeriesExists() {
        when(mapper.state()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of(
                new TierRow("t1", 0, 1000, new BigDecimal("9999"))));

        Map<String, Object> result = service.publicState();

        assertThat(result).containsEntry("marketOpenState", "closed")
                .containsEntry("catalogAvailable", false)
                .containsEntry("tradeAvailable", false)
                .containsEntry("tradeBlockedReason", "GENESIS_SERIES_UNAVAILABLE")
                .containsEntry("seriesSetupRequired", true)
                .containsEntry("seriesInitializationAvailable", true)
                .containsEntry("seriesRecoveryRequired", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminOverviewDoesNotDisplayOpenWhenActiveSeriesIsMissing() {
        when(mapper.state()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of(
                new TierRow("t1", 0, 1000, new BigDecimal("9999"))));

        Map<String, Object> data = service.enrich(ApiResult.ok(Map.of(
                "market", Map.of("enabled", true), "stats", Map.of("totalSlots", 0)))).getData();
        Map<String, Object> market = (Map<String, Object>) data.get("market");

        assertThat(data).containsEntry("catalogAvailable", false)
                .containsEntry("tradeAvailable", false)
                .containsEntry("seriesSetupRequired", true);
        assertThat(market).containsEntry("marketOpenState", "closed")
                .containsEntry("configuredMarketOpenState", "open")
                .containsEntry("prerequisiteStatus", "GENESIS_SERIES_UNAVAILABLE");
    }

    @Test
    void openingMarketRejectsMissingSeriesBeforePersistingOpenState() {
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "closed", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of(
                new TierRow("t1", 0, 1000, new BigDecimal("9999"))));

        assertThatThrownBy(() -> service.updateMarketStateOnce(
                new GenesisCatalogService.MarketStateRequest("open", "open after validation",
                        "superadmin", "default", 2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_SERIES_UNAVAILABLE");
                });

        verify(mapper, never()).updateMarketState(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void initializeSeriesDerivesSupplyAndOpeningPriceAndBumpsTheClosedStateVersion() {
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "closed", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.seriesCodeCount("GENESIS-2026")).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of(
                new TierRow("t1", 0, 400, new BigDecimal("9999")),
                new TierRow("t2", 400, 1000, new BigDecimal("12000"))));
        when(mapper.insertActiveSeries(any(SeriesBootstrapRow.class))).thenReturn(1);
        when(mapper.updateMarketState(eq("closed"), eq("default"), anyString(), eq(2L))).thenReturn(1);

        ApiResult<Void> result = service.initializeSeriesOnce(
                new GenesisCatalogService.SeriesBootstrapRequest("GENESIS-2026", "Genesis 2026",
                        "initialize missing active series", "superadmin"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<SeriesBootstrapRow> row = ArgumentCaptor.forClass(SeriesBootstrapRow.class);
        verify(mapper).insertActiveSeries(row.capture());
        assertThat(row.getValue().seriesCode()).isEqualTo("GENESIS-2026");
        assertThat(row.getValue().totalSupply()).isEqualTo(1000);
        assertThat(row.getValue().priceUsdt()).isEqualByComparingTo("9999");
        assertThat(row.getValue().royaltyBps()).isZero();
        assertThat(row.getValue().dailyEmissionRatePct()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.getValue().dividendBaseFormula()).isEmpty();
        verify(mapper).updateMarketState(eq("closed"), eq("default"), anyString(), eq(2L));
    }

    @Test
    void missingSeriesWithoutTiersRequiresFirstTierBeforeInitialization() {
        when(mapper.state()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of());

        assertThat(service.publicState())
                .containsEntry("marketOpenState", "closed")
                .containsEntry("tradeBlockedReason", "GENESIS_TIERS_UNAVAILABLE")
                .containsEntry("seriesSetupRequired", true)
                .containsEntry("seriesInitializationAvailable", false)
                .containsEntry("seriesRecoveryRequired", false);
    }

    @Test
    void historicalHoldingsRequireControlledSeriesMigrationAndCannotBeInitializedOver() {
        when(mapper.state()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(3L);

        assertThat(service.publicState())
                .containsEntry("tradeBlockedReason", "GENESIS_SERIES_RECOVERY_REQUIRED")
                .containsEntry("seriesInitializationAvailable", false)
                .containsEntry("seriesRecoveryRequired", true);

        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        assertThatThrownBy(() -> service.initializeSeriesOnce(
                new GenesisCatalogService.SeriesBootstrapRequest("GENESIS-2026", "Genesis 2026",
                        "restore historical catalog safely", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("GENESIS_SERIES_RECOVERY_REQUIRED"));
        verify(mapper, never()).insertActiveSeries(any());
    }

    @Test
    void createsTheFirstTierFromZeroWhenCatalogHasNoHoldings() {
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "closed", 2L,
                "default", "seed", 3L));
        when(mapper.activeTiers()).thenReturn(List.of());
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.insertTier(any(TierRow.class))).thenReturn(1);
        when(mapper.advanceTierVersion(7L, 4L)).thenReturn(1);

        assertThat(service.createTierOnce(new GenesisCatalogService.TierRequest(
                1000, new BigDecimal("9999"), 7L, "create first quote tier", "superadmin")).getCode()).isZero();

        ArgumentCaptor<TierRow> tier = ArgumentCaptor.forClass(TierRow.class);
        verify(mapper).insertTier(tier.capture());
        assertThat(tier.getValue()).isEqualTo(new TierRow("t3", 0, 1000, new BigDecimal("9999")));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sameIdempotencyKeyReplaysSamePayloadAndRejectsPayloadConflict() {
        Map<String, String> hashes = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, ApiResult<Void>> responses = new java.util.concurrent.ConcurrentHashMap<>();
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    String hash = invocation.getArgument(2);
                    String previous = hashes.putIfAbsent(key, hash);
                    if (previous != null && !previous.equals(hash)) {
                        throw new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                    }
                    if (responses.containsKey(key)) return responses.get(key);
                    ApiResult<Void> response = ((Supplier<ApiResult<Void>>) invocation.getArgument(4)).get();
                    responses.put(key, response);
                    return response;
                });
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "closed", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.seriesCodeCount(anyString())).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of(new TierRow("t1", 0, 1000, new BigDecimal("9999"))));
        when(mapper.insertActiveSeries(any())).thenReturn(1);
        when(mapper.updateMarketState(anyString(), anyString(), anyString(), anyLong())).thenReturn(1);
        var request = new GenesisCatalogService.SeriesBootstrapRequest(
                "GENESIS-2026", "Genesis 2026", "initialize catalog safely", "superadmin");

        assertThat(service.initializeSeries("same-key", request).getCode()).isZero();
        assertThat(service.initializeSeries("same-key", request).getCode()).isZero();
        assertThatThrownBy(() -> service.initializeSeries("same-key",
                new GenesisCatalogService.SeriesBootstrapRequest(
                        "GENESIS-2027", "Genesis 2027", "initialize catalog safely", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));
        verify(mapper, times(1)).insertActiveSeries(any());
    }

    @Test
    void concurrentInitializationsAllowOnlyOneSeriesInsert() throws Exception {
        GenesisCatalogMapper concurrentMapper = mock(GenesisCatalogMapper.class);
        GenesisCatalogService concurrentService = new GenesisCatalogService(concurrentMapper,
                idempotency, audit, Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC));
        CyclicBarrier readers = new CyclicBarrier(2);
        AtomicInteger insertWinner = new AtomicInteger();
        when(concurrentMapper.lockState()).thenReturn(new CatalogState(1L, 7L, "closed", 2L,
                "default", "seed", 3L));
        when(concurrentMapper.activeSeriesCount()).thenAnswer(invocation -> {
            readers.await(2, TimeUnit.SECONDS);
            return 0L;
        });
        when(concurrentMapper.soldCount()).thenReturn(0L);
        when(concurrentMapper.seriesCodeCount(anyString())).thenReturn(0L);
        when(concurrentMapper.activeTiers()).thenReturn(List.of(
                new TierRow("t1", 0, 1000, new BigDecimal("9999"))));
        when(concurrentMapper.insertActiveSeries(any())).thenAnswer(invocation ->
                insertWinner.getAndIncrement() == 0 ? 1 : 0);
        when(concurrentMapper.updateMarketState(anyString(), anyString(), anyString(), anyLong())).thenReturn(1);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> initializeOutcome(concurrentService, "A"));
            var second = pool.submit(() -> initializeOutcome(concurrentService, "B"));
            assertThat(List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "GENESIS_SERIES_INIT_CONFLICT");
            verify(concurrentMapper, times(1)).updateMarketState(anyString(), anyString(), anyString(), anyLong());
            assertThat(GenesisCatalogMapper.class.getMethod("lockState")
                    .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0])
                    .containsIgnoringCase("FOR UPDATE");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedMarketCloseEscapesTheIdempotentTransactionAndDoesNotAuditSuccess() throws Exception {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<ApiResult<Void>>) invocation.getArgument(4)).get());
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.seriesCodeCount(anyString())).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of(new TierRow("t1", 0, 1000, new BigDecimal("9999"))));
        when(mapper.insertActiveSeries(any())).thenReturn(1);
        when(mapper.updateMarketState(anyString(), anyString(), anyString(), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.initializeSeries("rollback-key",
                new GenesisCatalogService.SeriesBootstrapRequest(
                        "GENESIS-2026", "Genesis 2026", "rollback failed close", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("GENESIS_MARKET_STATE_CONFLICT"));
        verifyNoInteractions(audit);
        Transactional transaction = AdminIdempotencyTransactionExecutor.class
                .getMethod("runClaimed", Long.class, Supplier.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.rollbackFor()).contains(Exception.class);
    }

    @Test
    void failedMarketCloseTriggersRollbackForSeriesInsertAndStateChangeTransaction() {
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
        when(mapper.soldCount()).thenReturn(0L);
        when(mapper.seriesCodeCount(anyString())).thenReturn(0L);
        when(mapper.activeTiers()).thenReturn(List.of(new TierRow("t1", 0, 1000, new BigDecimal("9999"))));
        when(mapper.insertActiveSeries(any())).thenReturn(1);
        when(mapper.updateMarketState(anyString(), anyString(), anyString(), anyLong())).thenReturn(0);
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        AdminIdempotencyTransactionExecutor target = new AdminIdempotencyTransactionExecutor(
                mock(AdminIdempotencyRecordMapper.class), new ObjectMapper(),
                mock(AdminIdempotencyExpiryTransitionExecutor.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        AdminIdempotencyTransactionExecutor executor =
                (AdminIdempotencyTransactionExecutor) factory.getProxy();

        assertThatThrownBy(() -> executor.runClaimed(53L, () -> service.initializeSeriesOnce(
                new GenesisCatalogService.SeriesBootstrapRequest(
                        "GENESIS-2026", "Genesis 2026", "rollback failed close", "superadmin"))))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("GENESIS_MARKET_STATE_CONFLICT"));

        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).isZero();
        assertThat(transactions.rolledBack).isEqualTo(1);
        verify(mapper).insertActiveSeries(any());
        verifyNoInteractions(audit);
    }

    private String initializeOutcome(GenesisCatalogService target, String suffix) {
        try {
            target.initializeSeriesOnce(new GenesisCatalogService.SeriesBootstrapRequest(
                    "GENESIS-" + suffix, "Genesis " + suffix, "concurrent initialize safely", "superadmin"));
            return "ok";
        } catch (BizException ex) {
            return ex.getMessage();
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private int begun;
        private int committed;
        private int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }

}
