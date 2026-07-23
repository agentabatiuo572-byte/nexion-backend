package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class G4AdminCommandServiceTest {
    private final OpsNexMarketService market = mock(OpsNexMarketService.class);
    private final AppGenesisMapper mapper = mock(AppGenesisMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final G4AdminCommandService service = new G4AdminCommandService(
            market, mapper, config, idempotency, outbox, audit,
            Clock.fixed(Instant.parse("2026-07-22T05:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void emissionUsesImmutableSeriesUnitPriceAndPublishesPerHoldingDividendEvent() {
        when(config.activeValue("growth.phase.genesis_emissions_open")).thenReturn(Optional.of("1"));
        when(mapper.lockActiveSeries()).thenReturn(new AppGenesisMapper.SeriesRow(
                1L, "genesis-main", "Genesis Node", 1000, new BigDecimal("9999"),
                250, new BigDecimal("0.1"), "ACTIVE"));
        when(mapper.lockEmissionHoldings()).thenReturn(List.of(new AppGenesisMapper.HoldingRow(
                10L, "GN-0001", 42L, "ORDER-SECONDARY", "genesis-main",
                new BigDecimal("25000"), "ACTIVE", null, null, null)));
        when(mapper.insertEmissionBatch(any())).thenReturn(1);
        when(mapper.lockPendingEmissionItems("20260722")).thenReturn(List.of(
                new AppGenesisMapper.EmissionItemRow(100L, "20260722", "GN-0001", 42L,
                        new BigDecimal("9.999000"), "PENDING")));
        when(mapper.lockWallet(42L)).thenReturn(new BigDecimal("100"));
        when(mapper.creditWallet(42L, new BigDecimal("9.999000"))).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.markEmissionPaid(eq(100L), any())).thenReturn(1);
        when(mapper.userPolicy(42L)).thenReturn(
                new AppGenesisMapper.UserPolicyRow(42L, "APPROVED", "VN", "P1", 4, "2026-W30"));
        when(market.genesisOverview()).thenReturn(ApiResult.ok(new LinkedHashMap<>(Map.of("domain", "G4"))));

        var result = service.rerunEmission("g4-batch-1", "20260722", request("rerun daily emission", "FIN-20260722"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AppGenesisMapper.EmissionBatchWrite> batch = ArgumentCaptor.forClass(AppGenesisMapper.EmissionBatchWrite.class);
        ArgumentCaptor<AppGenesisMapper.EmissionItemWrite> item = ArgumentCaptor.forClass(AppGenesisMapper.EmissionItemWrite.class);
        verify(mapper).insertEmissionBatch(batch.capture());
        verify(mapper).insertEmissionItem(item.capture());
        assertThat(batch.getValue().totalAmountUsdt()).isEqualByComparingTo("9.999000");
        assertThat(item.getValue().amountUsdt()).isEqualByComparingTo("9.999000");
        verify(outbox).publishUserEvent(anyString(), eq("GN-0001"), eq("genesis.dividend_paid"),
                eq(42L), eq("P1"), eq(4), eq("2026-W30"), any());
    }

    @Test
    void parameterChangeWritesRequiredAuditInsideCommandTransaction() {
        NexMarketValueUpdateRequest request = request("approved royalty update", null);
        when(market.updateGenesisParam("g4-param-1", "royalty", request))
                .thenReturn(ApiResult.ok(new LinkedHashMap<>(Map.of("domain", "G4"))));

        assertThat(service.updateParam("royalty", "g4-param-1", request).getCode()).isZero();

        verify(audit).recordRequired(any());
        verify(outbox).publish(anyString(), anyString(), eq("admin.genesis_param_changed"), any());
    }

    private NexMarketValueUpdateRequest request(String reason, String decisionRef) {
        return new NexMarketValueUpdateRequest("2.5", reason, "superadmin", decisionRef, null, null);
    }
}
