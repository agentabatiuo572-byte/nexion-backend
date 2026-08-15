package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppTeamInsightsServiceTest {
    @Test
    void productionLeaderboardAndCommissionAreSelfScopedAndServerBacked() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.leaderboard("week", 0)).thenReturn(List.of(new AppTeamInsightsMapper.LeaderboardRow(
                1, 7L, "Alice", "V5", new BigDecimal("42.5"), 3, 9, 1)));
        when(mapper.commissionEvents(7L, 100)).thenReturn(List.of(new AppTeamInsightsMapper.CommissionRow(
                11L, "DIRECT", 8L, "Bob", 1, "ORD-1", new BigDecimal("99"),
                new BigDecimal("9.9"), BigDecimal.ZERO, "UNLOCKED", LocalDateTime.now(), LocalDateTime.now())));
        var service = new AppTeamInsightsService(mapper, new MockEnvironment());

        var board = service.leaderboard(7L, "week");
        var commissions = service.commissions(7L);

        assertThat(board.getCode()).isZero();
        assertThat(board.getData()).containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat(commissions.getCode()).isZero();
        verify(mapper).leaderboard("week", 0);
        verify(mapper).commissionEvents(7L, 100);
    }

    @Test
    void isolatedProfileRejectsProductionIdentityBeforeProjectionRead() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-1");
        environment.setActiveProfiles("local-sandbox");

        assertThatThrownBy(() -> new AppTeamInsightsService(mapper, environment).leaderboard(7L, "week"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("TEAM_SANDBOX_USER_REQUIRED");
        verify(mapper).userScope(7L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void unilevelProjectionIsServerOwnedAndIncludesCycleLayerSourceAndCurrencySplit() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.unilevelEvents(7L, 0, "week")).thenReturn(List.of(
                new AppTeamInsightsMapper.UnilevelRow(21L, 8L, "Bob", 1, "ORD-1", "2026-W33",
                        new BigDecimal("99"), new BigDecimal("9.9"), new BigDecimal("50"), "USDT",
                        "COOLING", LocalDateTime.of(2026, 8, 13, 0, 0), LocalDateTime.of(2026, 9, 12, 0, 0)),
                new AppTeamInsightsMapper.UnilevelRow(22L, 8L, "Bob", 1, "ORD-1", "2026-W33",
                        new BigDecimal("99"), BigDecimal.ZERO, new BigDecimal("495"), "NEX",
                        "COOLING", LocalDateTime.of(2026, 8, 13, 0, 0), LocalDateTime.of(2026, 9, 12, 0, 0))));

        var result = new AppTeamInsightsService(mapper, new MockEnvironment()).unilevel(7L, "week");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "server").containsEntry("period", "week");
        assertThat(result.getData().get("events").toString()).contains("2026-W33", "layer", "sourceUserId");
        assertThat(result.getData().get("split").toString()).contains("direct", "extended", "amountUSDT", "amountNEX");
        verify(mapper).unilevelEvents(7L, 0, "week");
    }

}
