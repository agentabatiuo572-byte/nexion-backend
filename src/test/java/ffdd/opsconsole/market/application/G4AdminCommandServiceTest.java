package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
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
import org.springframework.core.env.Environment;

class G4AdminCommandServiceTest {
    private final OpsNexMarketService market = mock(OpsNexMarketService.class);
    private final AppGenesisMapper mapper = mock(AppGenesisMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
    private final Environment environment = mock(Environment.class);
    private final G4AdminCommandService service = new G4AdminCommandService(
            market, mapper, config, idempotency, outbox, audit,
            Clock.fixed(Instant.parse("2026-07-22T05:00:00Z"), ZoneOffset.UTC), earningsRelease, environment);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void sandboxUnknownAndMixedRuntimeRejectEmissionBeforeIdempotencyOrAnyGenesisLock() {
        for (String[] profiles : List.of(
                new String[] {"test"},
                new String[] {"qa"},
                new String[] {"prod", "dev"})) {
            when(environment.getActiveProfiles()).thenReturn(profiles);

            assertThatThrownBy(() -> service.rerunEmission("g4-runtime-" + profiles.length, "20260722",
                    request("runtime fence", "FIN-RUNTIME")))
                    .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                    .hasMessageMatching("G4_GENESIS_EMISSION_(SANDBOX_UNAVAILABLE|RUNTIME_UNSUPPORTED)");
        }
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
        verifyNoGenesisEmissionInteraction();
    }

    @Test
    void disabledGenesisKillSwitchRejectsBeforeBatchOrLedgerWrite() {
        when(config.activeValue("growth.phase.genesis_emissions_open")).thenReturn(Optional.of("1"));
        when(config.activeValue("killswitch.genesis")).thenReturn(Optional.of("disabled"));

        assertThatThrownBy(() -> service.rerunEmission("g4-kill-switch", "20260722",
                request("kill switch fence", "FIN-KILL")))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("G4_GENESIS_KILLSWITCH_CLOSED");
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
        verifyNoGenesisEmissionInteraction();
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
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.markEmissionPaid(eq(100L), any())).thenReturn(1);
        when(mapper.userPolicy(42L)).thenReturn(
                new AppGenesisMapper.UserPolicyRow(42L, "VN", "P1", 120, 4, "2026-W30"));
        when(market.genesisOverview()).thenReturn(ApiResult.ok(new LinkedHashMap<>(Map.of("domain", "G4"))));

        var result = service.rerunEmission("g4-batch-1", "20260722", request("rerun daily emission", "FIN-20260722"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AppGenesisMapper.EmissionBatchWrite> batch = ArgumentCaptor.forClass(AppGenesisMapper.EmissionBatchWrite.class);
        ArgumentCaptor<AppGenesisMapper.EmissionItemWrite> item = ArgumentCaptor.forClass(AppGenesisMapper.EmissionItemWrite.class);
        verify(mapper).insertEmissionBatch(batch.capture());
        verify(mapper).insertEmissionItem(item.capture());
        assertThat(batch.getValue().totalAmountUsdt()).isEqualByComparingTo("9.999000");
        assertThat(item.getValue().amountUsdt()).isEqualByComparingTo("9.999000");
        verify(outbox).publishUserEvent(anyString(), eq("GN-0001"), eq("genesis.emission_paid"),
                eq(42L), eq("P1"), eq(4), eq("2026-W30"), any());
    }

    @Test
    void parameterChangeDelegatesSingleRequiredAuditToMarketService() {
        NexMarketValueUpdateRequest request = request("approved royalty update", null);
        when(market.updateGenesisParam("g4-param-1", "royalty", request))
                .thenReturn(ApiResult.ok(new LinkedHashMap<>(Map.of("domain", "G4"))));

        assertThat(service.updateParam("royalty", "g4-param-1", request).getCode()).isZero();

        verify(audit, never()).recordRequired(any());
        verify(outbox).publish(anyString(), anyString(), eq("admin.genesis_param_changed"), any());
    }

    @Test
    void completedNaturalBatchKeyReplaysWithoutDuplicateWalletLedgerEventOrAudit() {
        when(config.activeValue("growth.phase.genesis_emissions_open")).thenReturn(Optional.of("1"));
        when(mapper.lockActiveSeries()).thenReturn(new AppGenesisMapper.SeriesRow(
                1L, "genesis-main", "Genesis Node", 1000, new BigDecimal("9999"),
                250, new BigDecimal("0.1"), "ACTIVE"));
        when(mapper.lockEmissionBatch("20260722")).thenReturn(new AppGenesisMapper.EmissionBatchRow(
                "20260722", "COMPLETED", 1, new BigDecimal("9.999000")));
        when(market.genesisOverview()).thenReturn(ApiResult.ok(new LinkedHashMap<>(Map.of("domain", "G4"))));

        var result = service.rerunEmission("different-client-key", "20260722",
                request("replay completed emission", "FIN-20260722"));

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        var updated = (Map<String, Object>) result.getData().get("updated");
        assertThat(updated).containsEntry("replayed", true).containsEntry("paidCount", 1);
        verify(mapper, never()).lockEmissionHoldings();
        verify(mapper, never()).creditWallet(any(), any());
        verify(mapper, never()).insertLedger(any());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
        verify(audit, never()).recordRequired(any());
    }

    private NexMarketValueUpdateRequest request(String reason, String decisionRef) {
        return new NexMarketValueUpdateRequest("2.5", reason, "superadmin", decisionRef, null, null);
    }

    private void verifyNoGenesisEmissionInteraction() {
        verify(mapper, never()).lockActiveSeries();
        verify(mapper, never()).lockEmissionBatch(anyString());
        verify(mapper, never()).lockEmissionHoldings();
        verify(mapper, never()).insertEmissionBatch(any());
        verify(mapper, never()).insertEmissionItem(any());
        verify(mapper, never()).lockPendingEmissionItems(anyString());
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).insertLedger(any());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
    }
}
