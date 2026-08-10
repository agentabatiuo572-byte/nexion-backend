package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.dto.F5CommissionQuery;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class F5CommissionReadVersionTest {
    @Test
    void canonicalReadPreservesVersionAndFrozenProvenanceForRefreshAndRelogin() {
        F5CommissionMapper mapper = mock(F5CommissionMapper.class);
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("commissionId", "CM-71");
        source.put("eventId", 71L);
        source.put("userId", 42L);
        source.put("kind", "network");
        source.put("currency", "USDT");
        source.put("amount", new BigDecimal("10"));
        source.put("status", "frozen");
        source.put("version", 6L);
        source.put("frozenFromStatus", "COOLING");
        source.put("ledgerBizNo", "F2-NETWORK-71");
        when(mapper.queryEvents(null, null, null, null, null, null, 21)).thenReturn(List.of(source));
        when(mapper.queryEvents(null, null, null, null, null, null, 200)).thenReturn(List.of(source));
        when(mapper.recentOperations(50)).thenReturn(List.of());
        when(mapper.activeSuspensions(100)).thenReturn(List.of());
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(BigDecimal.ONE, BigDecimal.ZERO));
        F5CommissionService service = new F5CommissionService(mapper, config, coverage,
                mock(TreasuryLedgerPostingFacade.class), mock(AuditLogService.class),
                mock(EventOutboxService.class), mock(AdminIdempotencyService.class));

        Map<String, Object> result = service.overview(
                new F5CommissionQuery(null, null, null, null, null, null, 20)).getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("status", "frozen")
                .containsEntry("version", 6L)
                .containsEntry("frozenFromStatus", "COOLING")
                .containsEntry("ledgerBizNo", "F2-NETWORK-71"));
    }

    @Test
    void overviewFailsClosedWhenOperationHistoryCannotBeRead() {
        F5CommissionMapper mapper = mock(F5CommissionMapper.class);
        when(mapper.queryEvents(null, null, null, null, null, null, 21)).thenReturn(List.of());
        when(mapper.queryEvents(null, null, null, null, null, null, 200)).thenReturn(List.of());
        when(mapper.recentOperations(50)).thenThrow(new IllegalStateException("operation table unavailable"));
        F5CommissionService service = service(mapper);

        assertThatThrownBy(() -> service.overview(
                new F5CommissionQuery(null, null, null, null, null, null, 20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operation table unavailable");
    }

    @Test
    void overviewFailsClosedWhenActiveSuspensionsCannotBeRead() {
        F5CommissionMapper mapper = mock(F5CommissionMapper.class);
        when(mapper.queryEvents(null, null, null, null, null, null, 21)).thenReturn(List.of());
        when(mapper.queryEvents(null, null, null, null, null, null, 200)).thenReturn(List.of());
        when(mapper.recentOperations(50)).thenReturn(List.of());
        when(mapper.activeSuspensions(100)).thenThrow(new IllegalStateException("suspension table unavailable"));
        F5CommissionService service = service(mapper);

        assertThatThrownBy(() -> service.overview(
                new F5CommissionQuery(null, null, null, null, null, null, 20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("suspension table unavailable");
    }

    @Test
    void overviewFailsClosedWhenFullAggregateContainsALegacyCurrency() {
        F5CommissionMapper mapper = mock(F5CommissionMapper.class);
        when(mapper.queryEvents(null, null, null, null, null, null, 21)).thenReturn(List.of());
        when(mapper.queryEvents(null, null, null, null, null, null, 200)).thenReturn(List.of());
        when(mapper.aggregateCommissionKinds()).thenReturn(List.of(Map.of(
                "kind", "network", "currency", "BTC", "amount", BigDecimal.ONE, "count", 1L)));
        when(mapper.aggregateCommissionStatuses()).thenReturn(List.of());
        when(mapper.recentOperations(50)).thenReturn(List.of());
        when(mapper.activeSuspensions(100)).thenReturn(List.of());

        assertThatThrownBy(() -> service(mapper).overview(
                new F5CommissionQuery(null, null, null, null, null, null, 20)))
                .isInstanceOf(BizException.class)
                .hasMessage("F5_COMMISSION_CURRENCY_UNKNOWN");
    }

    @Test
    void overviewFailsClosedWhenAFullTableProbeFindsALegacyKind() {
        F5CommissionMapper mapper = mock(F5CommissionMapper.class);
        when(mapper.queryEvents(null, null, null, null, null, null, 21)).thenReturn(List.of());
        when(mapper.queryEvents(null, null, null, null, null, null, 200)).thenReturn(List.of());
        when(mapper.aggregateCommissionKinds()).thenReturn(List.of());
        when(mapper.aggregateCommissionStatuses()).thenReturn(List.of());
        when(mapper.unknownCommissionKindCount()).thenReturn(1L);
        when(mapper.recentOperations(50)).thenReturn(List.of());
        when(mapper.activeSuspensions(100)).thenReturn(List.of());

        assertThatThrownBy(() -> service(mapper).overview(
                new F5CommissionQuery(null, null, null, null, null, null, 20)))
                .isInstanceOf(BizException.class)
                .hasMessage("F5_COMMISSION_KIND_UNKNOWN");
    }

    private static F5CommissionService service(F5CommissionMapper mapper) {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(BigDecimal.ONE, BigDecimal.ZERO));
        return new F5CommissionService(mapper, config, coverage,
                mock(TreasuryLedgerPostingFacade.class), mock(AuditLogService.class),
                mock(EventOutboxService.class), mock(AdminIdempotencyService.class));
    }
}
