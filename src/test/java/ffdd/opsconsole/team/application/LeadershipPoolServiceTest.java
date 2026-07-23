package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F4 LeadershipPoolService 单元测试。
 * 覆盖:settle 票权按 votes 比例分配、injectAndSettle 注入(weeklyVolume×injectRate 默认5%)、
 * 幂等(同 weekKey 已结算则跳过)。
 */
@ExtendWith(MockitoExtension.class)
class LeadershipPoolServiceTest {

    @Mock private TeamCommissionMapper teamCommissionMapper;
    @Mock private TeamCommissionRepository commissionRepository;
    @Mock private TreasuryLedgerPostingFacade ledgerPostingFacade;
    @Mock private PlatformConfigFacade configFacade;

    @InjectMocks private LeadershipPoolService service;

    private Map<String, Object> voter(long userId, int votes) {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", userId);
        m.put("votes", votes);
        return m;
    }

    @Test
    void settle_distributesPoolByVotesRatio() {
        // user1 votes=1, user2 votes=3;pool=$400 → 100 + 300(按 votes 比例)
        when(teamCommissionMapper.listV3PlusVoters()).thenReturn(List.of(
                voter(1001L, 1), voter(1002L, 3)));
        when(commissionRepository.insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString())).thenReturn(11L, 12L);

        int settled = service.settle(new BigDecimal("400"), "202630");

        assertThat(settled).isEqualTo(2);
        // user1: 1/4 × 400 = 100;user2: 3/4 × 400 = 300
        verify(commissionRepository).insertCommissionEvent(eq(1001L), eq("leadership"), eq(null),
                eq("USDT"), eq(new BigDecimal("100.000000")), any(BigDecimal.class),
                eq("UNLOCKED"), anyInt(), anyString());
        verify(commissionRepository).insertCommissionEvent(eq(1002L), eq("leadership"), eq(null),
                eq("USDT"), eq(new BigDecimal("300.000000")), any(BigDecimal.class),
                eq("UNLOCKED"), anyInt(), anyString());
        verify(ledgerPostingFacade, org.mockito.Mockito.times(2)).postLedgerEntry(anyString(),
                anyLong(), anyString(), anyString(), anyString(), any(BigDecimal.class),
                anyString(), anyString());
    }

    @Test
    void injectAndSettle_injectsWeeklyVolumeTimesDefaultRate() {
        // weekCode=202630,weeklyVolume=$10000,injectRate 默认5% → pool=$500;单 voter 全得
        when(teamCommissionMapper.countLeadershipByWeek("202630")).thenReturn(0);
        when(teamCommissionMapper.weeklyPlatformVolume(202630)).thenReturn(new BigDecimal("10000"));
        when(teamCommissionMapper.listV3PlusVoters()).thenReturn(List.of(voter(1001L, 1)));
        when(commissionRepository.insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString())).thenReturn(21L);

        int settled = service.injectAndSettle(202630);

        assertThat(settled).isEqualTo(1);
        // pool = 10000 × 0.05 = 500;user1 votes=1/1 → 500
        verify(commissionRepository).insertCommissionEvent(eq(1001L), eq("leadership"), eq(null),
                eq("USDT"), eq(new BigDecimal("500.000000")), any(BigDecimal.class),
                eq("UNLOCKED"), anyInt(), anyString());
    }

    @Test
    void injectAndSettle_idempotent_skipsAlreadySettledWeek() {
        // 同 weekKey 已结算 → countLeadershipByWeek > 0 → 跳过,不查 volume/不派发
        when(teamCommissionMapper.countLeadershipByWeek("202630")).thenReturn(1);

        int settled = service.injectAndSettle(202630);

        assertThat(settled).isEqualTo(-1);
        verify(teamCommissionMapper, never()).weeklyPlatformVolume(anyInt());
        verify(commissionRepository, never()).insertCommissionEvent(anyLong(), anyString(), any(),
                anyString(), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyInt(), anyString());
    }
}
