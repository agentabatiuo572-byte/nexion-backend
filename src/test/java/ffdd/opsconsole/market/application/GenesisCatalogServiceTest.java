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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.mapper.GenesisCatalogMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.CatalogState;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.SeriesBootstrapRow;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.TierRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class GenesisCatalogServiceTest {
    private final GenesisCatalogMapper mapper = mock(GenesisCatalogMapper.class);
    private final GenesisCatalogService service = new GenesisCatalogService(mapper,
            mock(AdminIdempotencyService.class), mock(AuditLogService.class),
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

        Map<String, Object> result = service.publicState();

        assertThat(result).containsEntry("marketOpenState", "closed")
                .containsEntry("catalogAvailable", false)
                .containsEntry("tradeAvailable", false)
                .containsEntry("tradeBlockedReason", "GENESIS_SERIES_UNAVAILABLE")
                .containsEntry("seriesSetupRequired", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminOverviewDoesNotDisplayOpenWhenActiveSeriesIsMissing() {
        when(mapper.state()).thenReturn(new CatalogState(1L, 7L, "open", 2L,
                "default", "seed", 3L));
        when(mapper.activeSeriesCount()).thenReturn(0L);
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
                        500, new BigDecimal("0.100000"), "acquired_price_usdt",
                        "initialize missing active series", "superadmin"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<SeriesBootstrapRow> row = ArgumentCaptor.forClass(SeriesBootstrapRow.class);
        verify(mapper).insertActiveSeries(row.capture());
        assertThat(row.getValue().seriesCode()).isEqualTo("GENESIS-2026");
        assertThat(row.getValue().totalSupply()).isEqualTo(1000);
        assertThat(row.getValue().priceUsdt()).isEqualByComparingTo("9999");
        verify(mapper).updateMarketState(eq("closed"), eq("default"), anyString(), eq(2L));
    }

}
