package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F4 LeadershipPoolService 单元测试。
 * 覆盖:settle 票权按 votes 比例分配、injectAndSettle 严格消费权威配置、
 * 幂等(同 weekKey 已结算则跳过)。
 */
@ExtendWith(MockitoExtension.class)
class LeadershipPoolServiceTest {

    @Mock private TeamCommissionMapper teamCommissionMapper;
    @Mock private TeamCommissionRepository commissionRepository;
    @Mock private TreasuryLedgerPostingFacade ledgerPostingFacade;
    @Mock private PlatformConfigFacade configFacade;
    @Mock private AuditLogService auditLogService;
    @Mock private EventOutboxService eventOutboxService;
    @Mock private LeadershipPoolConfigAlertService settlementConfigAlertService;

    private LeadershipPoolService service;

    @BeforeEach
    void setUp() {
        service = new LeadershipPoolService(
                teamCommissionMapper, commissionRepository, ledgerPostingFacade, configFacade,
                auditLogService, eventOutboxService, new LeadershipPoolConfigGuard(configFacade),
                settlementConfigAlertService);
    }

    private Map<String, Object> voter(long userId, int votes) {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", userId);
        m.put("votes", votes);
        return m;
    }

    @Test
    void periodPrizeReadsIndependentAmountsForAllFourPeriods() {
        when(configFacade.activeValue("team.ui.F.pool.periodPrize"))
                .thenReturn(Optional.of(
                        "{\"today\":5000,\"week\":50000,\"month\":250000,\"allTime\":1000000}"));

        assertThat(service.configuredPeriodPrize("today")).isEqualByComparingTo("5000");
        assertThat(service.configuredPeriodPrize("week")).isEqualByComparingTo("50000");
        assertThat(service.configuredPeriodPrize("month")).isEqualByComparingTo("250000");
        assertThat(service.configuredPeriodPrize("allTime")).isEqualByComparingTo("1000000");
    }

    @Test
    void schedulerTickAfterMissedMidnightCatchesUpPersistedDailyCheckpoint() {
        when(configFacade.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "team.runtime.F.leaderboard.lastSettled.today" -> Optional.of("2026-08-08");
            case "team.runtime.F.leaderboard.lastSettled.week" -> Optional.of("2026-08-03");
            case "team.runtime.F.leaderboard.lastSettled.month" -> Optional.of("2026-07");
            default -> Optional.empty();
        });
        when(teamCommissionMapper.lockLeadershipSettlementMutex(anyInt())).thenReturn(1L);

        int settled = service.settleClosedLeaderboardPeriods(
                ZonedDateTime.of(2026, 8, 10, 0, 37, 0, 0, ZoneOffset.UTC));

        assertThat(settled).isZero();
        verify(configFacade).upsertAdminValue(
                "team.runtime.F.leaderboard.lastSettled.today", "2026-08-09",
                "TEXT", "team_runtime", "F16 leaderboard settlement checkpoint");
    }

    @Test
    void settle_distributesPoolByVotesRatio() {
        stubValidSettlementConfig();
        // user1 votes=1, user2 votes=3;pool=$400 → 100 + 300(按 votes 比例)
        when(teamCommissionMapper.listLeadershipVoters(3)).thenReturn(List.of(
                voter(1001L, 1), voter(1002L, 3)));
        when(commissionRepository.insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString())).thenReturn(11L, 12L);

        int settled = service.settle(new BigDecimal("400"), "202630");

        assertThat(settled).isEqualTo(2);
        // user1: 1/4 × 400 = 100;user2: 3/4 × 400 = 300
        verify(commissionRepository).insertCommissionEvent(eq(1001L), eq("leadership"), eq(null),
                eq("USDT"), eq(new BigDecimal("100.000000")), any(BigDecimal.class),
                eq("UNLOCKED"), eq(0), anyString());
        verify(commissionRepository).insertCommissionEvent(eq(1002L), eq("leadership"), eq(null),
                eq("USDT"), eq(new BigDecimal("300.000000")), any(BigDecimal.class),
                eq("UNLOCKED"), eq(0), anyString());
        verify(ledgerPostingFacade, org.mockito.Mockito.times(2)).postLedgerEntry(anyString(),
                anyLong(), anyString(), anyString(), anyString(), any(BigDecimal.class),
                anyString(), anyString());
    }

    @Test
    void injectAndSettle_missingAuthoritativeConfigurationFailsClosedBeforeAnyFundWrite() {
        assertThatThrownBy(() -> service.injectAndSettle(202630))
                .isInstanceOf(BizException.class)
                .hasMessage("F4_SETTLEMENT_CONFIG_UNAVAILABLE");
        verify(teamCommissionMapper, never()).ensureLeadershipSettlementMutex(anyInt());
        verify(commissionRepository, never()).insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString());
        verify(ledgerPostingFacade, never()).postLedgerEntry(anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void injectAndSettle_idempotent_skipsAlreadySettledWeek() {
        stubValidSettlementConfig();
        // 同 weekKey 已结算 → countLeadershipByWeek > 0 → 跳过,不查 volume/不派发
        when(teamCommissionMapper.countLeadershipByWeek("202630")).thenReturn(1);
        when(teamCommissionMapper.lockLeadershipSettlementMutex(202630)).thenReturn(1L);

        int settled = service.injectAndSettle(202630);

        assertThat(settled).isEqualTo(-1);
        verify(teamCommissionMapper, never()).weeklyPlatformVolume(anyInt());
        verify(commissionRepository, never()).insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString());
    }

    @Test
    void injectAndSettle_usesPercentConfigAndConfiguredUnlockRank() {
        when(teamCommissionMapper.lockLeadershipSettlementMutex(202630)).thenReturn(1L);
        when(teamCommissionMapper.countLeadershipByWeek("202630")).thenReturn(0);
        when(teamCommissionMapper.weeklyPlatformVolume(202630)).thenReturn(new BigDecimal("10000"));
        when(configFacade.activeValue("team.ui.F.pool.ratio"))
                .thenReturn(java.util.Optional.of("30"));
        when(configFacade.activeValue("team.ui.F.pool.unlockVRank"))
                .thenReturn(java.util.Optional.of("V4"));
        when(configFacade.activeValue("team.ui.F.pool.monthlyCap"))
                .thenReturn(java.util.Optional.of("5000"));
        when(configFacade.activeValue("team.ui.F.pool.settleCron"))
                .thenReturn(java.util.Optional.of("0 59 23 * * 0"));
        when(configFacade.activeValue("team.ui.F.pool.configVersion"))
                .thenReturn(java.util.Optional.of("7"));
        when(teamCommissionMapper.listLeadershipVoters(4)).thenReturn(List.of(voter(1004L, 8)));
        when(commissionRepository.insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString())).thenReturn(31L);

        int settled = service.injectAndSettle(202630);

        assertThat(settled).isEqualTo(1);
        verify(commissionRepository).insertCommissionEvent(
                eq(1004L), eq("leadership"), eq(null), eq("USDT"),
                eq(new BigDecimal("3000.000000")), any(BigDecimal.class),
                eq("UNLOCKED"), eq(0), anyString());
    }

    @Test
    void injectAndSettle_invalidRateFailsClosedBeforeSettlementMutexAndLedger() {
        when(configFacade.activeValue("team.ui.F.pool.configVersion")).thenReturn(Optional.of("8"));
        when(configFacade.activeValue("team.ui.F.pool.ratio")).thenReturn(Optional.of("not-a-rate"));

        assertThatThrownBy(() -> service.injectAndSettle(202630))
                .isInstanceOf(BizException.class)
                .hasMessage("F4_SETTLEMENT_CONFIG_UNAVAILABLE");

        verify(teamCommissionMapper, never()).ensureLeadershipSettlementMutex(anyInt());
        verify(commissionRepository, never()).insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString());
        verify(ledgerPostingFacade, never()).postLedgerEntry(anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void injectAndSettle_historicalBareSixMeansSixPercentNeverSixHundredPercent() {
        when(configFacade.activeValue("team.ui.F.pool.configVersion")).thenReturn(Optional.of("9"));
        when(configFacade.activeValue("team.ui.F.pool.ratio")).thenReturn(Optional.of("6"));
        when(configFacade.activeValue("team.ui.F.pool.unlockVRank")).thenReturn(Optional.of("V3"));
        when(configFacade.activeValue("team.ui.F.pool.monthlyCap")).thenReturn(Optional.of("5000"));
        when(configFacade.activeValue("team.ui.F.pool.settleCron")).thenReturn(Optional.of("0 59 23 * * 0"));
        when(teamCommissionMapper.lockLeadershipSettlementMutex(202630)).thenReturn(1L);
        when(teamCommissionMapper.weeklyPlatformVolume(202630)).thenReturn(new BigDecimal("10000"));
        when(teamCommissionMapper.listLeadershipVoters(3)).thenReturn(List.of(voter(1001L, 1)));
        when(commissionRepository.insertCommissionEvent(anyLong(), anyString(), any(), anyString(),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), anyInt(), anyString()))
                .thenReturn(41L);

        assertThat(service.injectAndSettle(202630)).isEqualTo(1);

        verify(commissionRepository).insertCommissionEvent(
                eq(1001L), eq("leadership"), eq(null), eq("USDT"),
                eq(new BigDecimal("600.000000")), any(BigDecimal.class),
                eq("UNLOCKED"), eq(0), anyString());
    }

    private void stubValidSettlementConfig() {
        when(configFacade.activeValue("team.ui.F.pool.configVersion")).thenReturn(Optional.of("7"));
        when(configFacade.activeValue("team.ui.F.pool.ratio")).thenReturn(Optional.of("5%"));
        when(configFacade.activeValue("team.ui.F.pool.unlockVRank")).thenReturn(Optional.of("V3"));
        when(configFacade.activeValue("team.ui.F.pool.monthlyCap")).thenReturn(Optional.of("5000"));
        when(configFacade.activeValue("team.ui.F.pool.settleCron")).thenReturn(Optional.of("0 59 23 * * 0"));
    }
}
