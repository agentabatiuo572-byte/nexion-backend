package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.market.mapper.AppRepurchaseMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppRepurchaseServiceTest {
    private final AppRepurchaseMapper mapper = mock(AppRepurchaseMapper.class);
    private final RiskDisclosureGateFacade disclosureGate = mock(RiskDisclosureGateFacade.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final GrowthRhythmFacade growthRhythmFacade = mock(GrowthRhythmFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T03:00:00Z"), ZoneOffset.UTC);
    private final AppRepurchaseService service = new AppRepurchaseService(
            mapper, disclosureGate, config, growthRhythmFacade, idempotency, outbox, audit, clock);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeIdempotentAction() {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(disclosureGate.checkUserGate(eq(7L), eq("staking"), anyString())).thenReturn(ApiResult.ok(null));
        when(mapper.latestNexUsdtPrice()).thenReturn(new BigDecimal("0.12"));
        GrowthRhythmSnapshot rhythm = mock(GrowthRhythmSnapshot.class);
        when(rhythm.currentMonth()).thenReturn(2);
        when(rhythm.totalMonths()).thenReturn(12);
        when(rhythm.reinvestMultiplier()).thenReturn(BigDecimal.ONE);
        when(growthRhythmFacade.snapshot()).thenReturn(rhythm);
    }

    @Test
    void exposesOnlyServerCanonicalConfigWithoutPointsReward() {
        when(mapper.product()).thenReturn(product());
        when(mapper.issuedTicketsThisMonth()).thenReturn(20L);
        when(mapper.configValue("wallet.exchange.nex_usdt_price")).thenReturn("1.25");
        when(mapper.configValue("G.genesis.lottery.monthlyCapacity")).thenReturn("1000");

        Map<String, Object> data = service.config().getData();

        assertThat(data).containsEntry("lockDays", 90).containsEntry("apyPct", new BigDecimal("35"))
                .containsEntry("pointsReward", false).containsEntry("g4LotteryCapacity", 1000L);
        assertThat(data.get("presets")).isEqualTo(List.of(
                new BigDecimal("100.000000"), new BigDecimal("200.000000"),
                new BigDecimal("500.000000"), new BigDecimal("1000.000000")));
    }

    @Test
    void openAtomicallyDebitsCreatesPositionLedgerTicketAndTwoCanonicalEvents() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.lockProduct()).thenReturn(product());
        when(mapper.issuedTicketsThisMonth()).thenReturn(20L);
        when(mapper.configValue("G.genesis.lottery.monthlyCapacity")).thenReturn("1000");
        when(mapper.lockWallet(7L)).thenReturn(new BigDecimal("500"));
        when(mapper.debitWallet(eq(7L), any())).thenReturn(1);
        when(mapper.insertPosition(any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.insertTicket(any())).thenReturn(1);
        when(mapper.attribution(7L)).thenReturn(new AppRepurchaseMapper.UserAttribution("P4", 6, "2026-W30"));
        when(outbox.publishUserEvent(anyString(), anyString(), anyString(), eq(7L), eq("P4"), eq(6), eq("2026-W30"), any()))
                .thenReturn("evt-1");
        when(mapper.wallet(7L)).thenReturn(new BigDecimal("300"));
        when(mapper.positions(7L)).thenReturn(List.of());

        ApiResult<Map<String, Object>> result = service.open(7L, "g7-open-1",
                new AppRepurchaseService.OpenRequest(new BigDecimal("200")));

        assertThat(result.getData()).containsEntry("walletBalanceUsdt", new BigDecimal("300.000000"));
        verify(mapper).debitWallet(7L, new BigDecimal("200.000000"));
        verify(mapper).insertPosition(any());
        verify(mapper).insertLedger(any());
        verify(mapper).insertTicket(any());
        verify(outbox).publishUserEvent(eq("REPURCHASE_ORDER"), anyString(), eq("wallet.reinvest"),
                eq(7L), eq("P4"), eq(6), eq("2026-W30"), any());
        verify(outbox).publishUserEvent(eq("REPURCHASE_ORDER"), anyString(), eq("staking.opened"),
                eq(7L), eq("P4"), eq(6), eq("2026-W30"), any());
    }

    @Test
    void g4CapacityFailsClosedBeforeAnyWalletDebit() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.lockProduct()).thenReturn(product());
        when(mapper.issuedTicketsThisMonth()).thenReturn(1000L);
        when(mapper.configValue("G.genesis.lottery.monthlyCapacity")).thenReturn("1000");

        assertThatThrownBy(() -> service.open(7L, "g7-open-capacity",
                new AppRepurchaseService.OpenRequest(new BigDecimal("200"))))
                .isInstanceOf(BizException.class).hasMessageContaining("G4_LOTTERY_CAPACITY_EXCEEDED");
    }

    @Test
    void unacknowledgedDisclosureFailsClosedBeforeAnyFinancialMutation() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.lockProduct()).thenReturn(product());
        when(disclosureGate.checkUserGate(7L, "staking", "g7-open-unacknowledged"))
                .thenReturn(ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED"));

        ApiResult<Map<String, Object>> result = service.open(7L, "g7-open-unacknowledged",
                new AppRepurchaseService.OpenRequest(new BigDecimal("200")));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("RISK_DISCLOSURE_ACK_REQUIRED");
        verify(disclosureGate).checkUserGate(7L, "staking", "g7-open-unacknowledged");
        verify(mapper, never()).debitWallet(any(), any());
        verify(mapper, never()).insertPosition(any());
        verify(mapper, never()).insertLedger(any());
        verify(mapper, never()).insertTicket(any());
        verify(outbox, never()).publishUserEvent(anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
        verify(audit, never()).recordRequiredForTrustedActor(any());
    }

    @Test
    void disclosureRequirementIsPerUserAndDoesNotTurnIntoAGlobalStop() {
        when(mapper.product()).thenReturn(product());
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.lockProduct()).thenReturn(product());
        when(config.activeValue("disclosure.gate.staking")).thenReturn(Optional.of("true"));
        when(mapper.issuedTicketsThisMonth()).thenReturn(20L);
        when(mapper.configValue("G.genesis.lottery.monthlyCapacity")).thenReturn("1000");
        when(mapper.lockWallet(7L)).thenReturn(new BigDecimal("500"));
        when(mapper.debitWallet(eq(7L), any())).thenReturn(1);
        when(mapper.insertPosition(any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.insertTicket(any())).thenReturn(1);
        when(mapper.attribution(7L)).thenReturn(new AppRepurchaseMapper.UserAttribution("P4", 6, "2026-W30"));
        when(outbox.publishUserEvent(anyString(), anyString(), anyString(), eq(7L), eq("P4"), eq(6), eq("2026-W30"), any()))
                .thenReturn("evt-1");
        when(mapper.wallet(7L)).thenReturn(new BigDecimal("300"));
        when(mapper.positions(7L)).thenReturn(List.of());
        when(disclosureGate.checkUserGate(7L, "staking", "g7-open-acknowledged"))
                .thenReturn(ApiResult.ok(null));

        Map<String, Object> config = service.config().getData();
        ApiResult<Map<String, Object>> result = service.open(7L, "g7-open-acknowledged",
                new AppRepurchaseService.OpenRequest(new BigDecimal("200")));

        assertThat(config).containsEntry("enabled", true).containsEntry("disclosureRequired", true);
        assertThat(result.getCode()).isZero();
        verify(disclosureGate).checkUserGate(7L, "staking", "g7-open-acknowledged");
        verify(mapper).debitWallet(7L, new BigDecimal("200.000000"));
    }

    @Test
    void disclosureFailureIsNotClaimedByIdempotencyAndSameKeySucceedsAfterAcknowledgment() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.lockProduct()).thenReturn(product());
        when(mapper.issuedTicketsThisMonth()).thenReturn(20L);
        when(mapper.configValue("G.genesis.lottery.monthlyCapacity")).thenReturn("1000");
        when(mapper.lockWallet(7L)).thenReturn(new BigDecimal("500"));
        when(mapper.debitWallet(eq(7L), any())).thenReturn(1);
        when(mapper.insertPosition(any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.insertTicket(any())).thenReturn(1);
        when(mapper.attribution(7L)).thenReturn(new AppRepurchaseMapper.UserAttribution("P4", 6, "2026-W30"));
        when(outbox.publishUserEvent(anyString(), anyString(), anyString(), eq(7L), eq("P4"), eq(6), eq("2026-W30"), any()))
                .thenReturn("evt-1");
        when(mapper.wallet(7L)).thenReturn(new BigDecimal("300"));
        when(mapper.positions(7L)).thenReturn(List.of());
        when(disclosureGate.checkUserGate(7L, "staking", "g7-open-same-key"))
                .thenReturn(ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED"), ApiResult.ok(null));

        ApiResult<Map<String, Object>> blocked = service.open(7L, "g7-open-same-key",
                new AppRepurchaseService.OpenRequest(new BigDecimal("200")));
        ApiResult<Map<String, Object>> accepted = service.open(7L, "g7-open-same-key",
                new AppRepurchaseService.OpenRequest(new BigDecimal("200")));

        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getMessage()).isEqualTo("RISK_DISCLOSURE_ACK_REQUIRED");
        assertThat(accepted.getCode()).isZero();
        verify(disclosureGate, times(2)).checkUserGate(7L, "staking", "g7-open-same-key");
        verify(idempotency, times(1)).execute(anyString(), eq("g7-open-same-key"), anyString(), eq(ApiResult.class), any());
        verify(mapper, times(1)).debitWallet(7L, new BigDecimal("200.000000"));
        verify(mapper, times(1)).insertPosition(any());
        verify(mapper, times(1)).insertLedger(any());
        verify(mapper, times(1)).insertTicket(any());
    }

    @Test
    void j1KillSwitchRemainsTheOnlyGlobalGateAndRunsBeforeUserDisclosure() {
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.lockProduct()).thenReturn(product());
        when(mapper.controlValue("killswitch.staking")).thenReturn("disabled");

        assertThatThrownBy(() -> service.open(7L, "g7-open-killed",
                new AppRepurchaseService.OpenRequest(new BigDecimal("200"))))
                .isInstanceOf(BizException.class).hasMessageContaining("REPURCHASE_GLOBAL_GATE_DISABLED");

        verify(disclosureGate, never()).checkUserGate(any(), anyString(), anyString());
        verify(mapper, never()).debitWallet(any(), any());
    }

    private AppRepurchaseMapper.ProductRow product() {
        return new AppRepurchaseMapper.ProductRow(77L, "REPURCHASE_90D", "复投增益 90 天", "USDT", 90,
                new BigDecimal("3500"), new BigDecimal("1500"), new BigDecimal("100"),
                new BigDecimal("1.5"), 1, "100 / 200 / 500 / 1000", "ACTIVE");
    }
}
