package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.OutboxProperties;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class LeadershipPoolOutboxContractTest {
    private final EventOutboxMapper outbox = mock(EventOutboxMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final EventOutboxService publisher = new EventOutboxService(outbox, json,
            new OutboxProperties(), mock(A4RuntimePolicyService.class));
    private final TeamCommissionMapper mapper = mock(TeamCommissionMapper.class);
    private final TeamCommissionRepository repository = mock(TeamCommissionRepository.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final LeadershipPoolService service = new LeadershipPoolService(mapper, repository, ledger, config,
            audit, publisher, new LeadershipPoolConfigGuard(config), mock(LeadershipPoolConfigAlertService.class));

    LeadershipPoolOutboxContractTest() {
        // Exact ACTIVE/full commission.paid rev312 properties read on 2026-10-17.
        // Fixture only: no database access or schema modification.
        when(outbox.findActiveSchema("commission.paid"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("money", 312, true));
        when(outbox.findLifecycleState("commission.paid")).thenReturn("full");
        when(outbox.listActiveProperties("commission.paid")).thenReturn(List.of(
                property("amount", "number", true), property("commission_event_id", "id", false),
                property("currency", "enum", true), property("daily_cap_applied", "boolean", false),
                property("kind", "enum", true), property("layer", "number", false),
                property("match_rate", "number", false), property("order_no", "id", false),
                property("smaller_track_gv", "number", false), property("source_user_id", "id", false)));
        when(config.activeValue(anyString())).thenAnswer(call -> Optional.ofNullable(switch ((String) call.getArgument(0)) {
            case "team.ui.F.pool.periodPrize" -> "{\"today\":1,\"week\":1,\"month\":1,\"allTime\":1}";
            case "team.ui.F.leaderboard.minUsd" -> "100";
            case "team.ui.F.pool.configVersion" -> "7";
            case "team.ui.F.pool.ratio" -> "5%";
            case "team.ui.F.pool.unlockVRank" -> "V3";
            case "team.ui.F.pool.monthlyCap" -> "5000";
            case "team.ui.F.pool.settleCron" -> "0 59 23 * * 0";
            default -> null;
        }));
        when(config.activeValueForUpdate("team.ui.F.leaderboard.paused")).thenReturn(Optional.of("off"));
        when(mapper.lockLeadershipSettlementMutex(anyInt())).thenReturn(1L);
        when(repository.insertCommissionEvent(anyLong(), anyString(), isNull(), eq("USDT"),
                any(), any(), eq("UNLOCKED"), eq(0), anyString())).thenReturn(101L, 102L);
    }

    private static EventOutboxMapper.SchemaPropertyGateRow property(String name, String type, boolean required) {
        return new EventOutboxMapper.SchemaPropertyGateRow(name, type, required);
    }

    @AfterEach void clearReplay() { A2ReplayContext.exitReplay(); }

    @Test
    void registryStillRejectsExtraKeysAndMissingRequiredFields() {
        assertThatThrownBy(() -> publisher.publish("LEADERBOARD_PRIZE", "fixture", "commission.paid",
                Map.of("userId", 7L, "amount", BigDecimal.ONE, "period", "allTime", "settlementKey", "allTime:lifetime")))
                .isInstanceOf(BizException.class).hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        assertThatThrownBy(() -> publisher.publish("LEADERBOARD_PRIZE", "fixture", "commission.paid",
                Map.of("userId", 7L, "amount", BigDecimal.ONE)))
                .isInstanceOf(BizException.class).hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
        verify(outbox, never()).insertEvent(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"today,2026-01-01,today:2026-01-01", "week,2026-01-05,week:2026-01-05",
            "month,2026-01,month:2026-01", "allTime,,allTime:lifetime"})
    void everyLeaderboardPeriodPublishesTheRegisteredFactsWithoutChangingAllocation(String period, String periodKey, String key) throws Exception {
        when(repository.leaderboardCandidates(eq(period), any(), any(), eq(new BigDecimal("100")), anyInt()))
                .thenReturn(List.of(Map.of("userId", 7L), Map.of("userId", 8L)));
        A2ReplayContext.enterReplay("fixture-leaderboard-contract");
        assertThat(service.settleApprovedLeaderboardPeriod(period, periodKey, "reviewer", "合法周期收益契约测试")).isEqualTo(2);
        var payloads = ArgumentCaptor.forClass(String.class);
        var aggregates = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(2)).insertEvent(anyString(), eq("LEADERBOARD_PRIZE"), aggregates.capture(),
                eq("commission.paid"), eq("commission.paid"), eq("money"), anyString(), anyInt(), anyString(),
                eq(true), eq(312), eq(true), eq(true), payloads.capture());
        assertThat(aggregates.getAllValues()).containsExactly("F4-LB-" + key + "-101", "F4-LB-" + key + "-102");
        for (int i = 0; i < 2; i++) {
            var event = json.readTree(payloads.getAllValues().get(i));
            assertThat(event.path("kind").asText()).isEqualTo("leaderboard_prize");
            assertThat(event.path("currency").asText()).isEqualTo("USDT");
            assertThat(event.path("commission_event_id").asLong()).isEqualTo(101L + i);
            assertThat(event.path("user_id").asLong()).isEqualTo(7L + i);
            assertThat(event.path("amount").decimalValue()).isEqualByComparingTo(i == 0 ? "0.250000" : "0.333333");
            assertThat(event.has("period") || event.has("settlementKey") || event.has("settlement_key")).isFalse();
        }
        verify(ledger).releaseCommissionFunds(101L);
        verify(ledger).releaseCommissionFunds(102L);
        verify(audit).recordRequired(any());
        when(mapper.countLeaderboardBySettlementKey(key)).thenReturn(1);
        assertThat(service.settleApprovedLeaderboardPeriod(period, periodKey, "reviewer", "合法周期收益契约测试")).isZero();
        verifyNoMoreInteractions(ledger);
    }

    @Test
    void existingLeadershipProducerAlsoPassesTheSameRealRegistryValidator() throws Exception {
        when(mapper.listLeadershipVoters(3)).thenReturn(List.of(Map.of("userId", 7L, "votes", 1)));
        assertThat(service.settle(BigDecimal.ONE, "202601")).isEqualTo(1);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).insertEvent(anyString(), eq("LEADERSHIP_COMMISSION"), eq("F4-POOL-202601-101"),
                eq("commission.paid"), eq("commission.paid"), eq("money"), anyString(), anyInt(), anyString(),
                eq(true), eq(312), eq(true), eq(true), payload.capture());
        var event = json.readTree(payload.getValue());
        assertThat(event.path("kind").asText()).isEqualTo("leadership");
        assertThat(event.path("currency").asText()).isEqualTo("USDT");
        assertThat(event.path("commission_event_id").asLong()).isEqualTo(101L);
        assertThat(event.path("amount").decimalValue()).isEqualByComparingTo("1");
    }
}
