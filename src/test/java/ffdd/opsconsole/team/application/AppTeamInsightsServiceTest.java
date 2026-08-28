package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppTeamInsightsServiceTest {
    @Test
    void leadershipRankDistributionGroupsByTheSelectedSourceColumnForOnlyFullGroupBy() throws Exception {
        var method = AppTeamInsightsMapper.class.getMethod("rankDistribution", Integer.class);
        var select = method.getAnnotation(org.apache.ibatis.annotations.Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        assertThat(sql).contains("GROUP BY u.v_rank")
                .doesNotContain("GROUP BY UPPER(u.v_rank)");
    }

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
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> new AppTeamInsightsService(mapper, environment).leaderboard(7L, "week"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("TEAM_SANDBOX_USER_REQUIRED");
        verify(mapper).userScope(7L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void sandboxInsightsReturnServerOwnedFacts() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        var result = new AppTeamInsightsService(mapper, environment).commissions(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "sandbox-run-20260816")
                .containsEntry("serverCanonical", true)
                .containsEntry("factStatus", "SIMULATED")
                .containsEntry("withdrawable", false)
                .containsEntry("payoutStatus", "NON_WITHDRAWABLE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) result.getData().get("events");
        assertThat(events).allSatisfy(event -> assertThat(event)
                .containsEntry("status", "SIMULATED")
                .containsEntry("settlementState", "SIMULATED")
                .containsEntry("withdrawable", false));
        verify(mapper).userScope(7L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void sandboxInsightsAreStableAndIsolatedByRunAndAccountWithPaging() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V5"));
        when(mapper.userScope(8L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");
        var service = new AppTeamInsightsService(mapper, environment);

        var first = service.leaderboard(7L, "week", 1, 3);
        var repeat = service.leaderboard(7L, "week", 1, 3);
        var otherAccount = service.leaderboard(8L, "week", 1, 3);
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "run", java.util.Map.of("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260817")));
        var otherRun = service.leaderboard(7L, "week", 1, 3);
        var secondPage = service.leaderboard(7L, "week", 2, 3);
        var commissionFirst = service.commissions(7L);
        var commissionRepeat = service.commissions(7L);
        var unilevelFirst = service.unilevel(7L, "week");
        var unilevelRepeat = service.unilevel(7L, "week");
        var poolFirst = service.leadershipPool(7L);
        var poolRepeat = service.leadershipPool(7L);

        assertThat(first.getData()).isEqualTo(repeat.getData());
        assertThat(first.getData()).isNotEqualTo(otherAccount.getData());
        assertThat(first.getData()).isNotEqualTo(otherRun.getData());
        assertThat(secondPage.getData()).containsEntry("page", 2L).containsEntry("pageSize", 3L);
        assertThat(commissionFirst.getData()).isEqualTo(commissionRepeat.getData());
        assertThat(unilevelFirst.getData()).isEqualTo(unilevelRepeat.getData());
        assertThat(poolFirst.getData()).isEqualTo(poolRepeat.getData());
        verify(mapper, times(10)).userScope(7L);
        verify(mapper).userScope(8L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void hugePageDoesNotOverflowIntoServerError() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        var result = new AppTeamInsightsService(mapper, environment)
                .leaderboard(7L, "week", Integer.MAX_VALUE, 100);

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getData().get("rows");
        assertThat(rows).isEmpty();
    }

    @Test
    void pageSizeUsesLongContractAndRejectsUnsafeValuesAsClientInput() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        var result = new AppTeamInsightsService(mapper, environment)
                .leaderboard(7L, "week", 1L, Long.MAX_VALUE);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("TEAM_LEADERBOARD_PAGE_INVALID");
    }

    @Test
    void runIdMustUseTheSharedEightToNinetySixCharacterContract() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-123");
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> new AppTeamInsightsService(mapper, environment).commissions(7L))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("TEAM_RUN_ID_REQUIRED");
    }

    @Test
    void sandboxInsightsRejectProductionIdentityBeforeGeneratingFacts() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> new AppTeamInsightsService(mapper, environment)
                .leaderboard(7L, "week", 1, 3))
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
        assertThat(result.getData().get("events").toString()).contains("2026-W33", "layer")
                .doesNotContain("sourceUserId");
        assertThat(result.getData().get("split").toString()).contains("direct", "extended", "amountUSDT", "amountNEX");
        verify(mapper).unilevelEvents(7L, 0, "week");
    }

    @Test
    void developmentAllowsAnyActiveDevelopmentAccountAndReadsCanonicalCommissionFacts() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V5"));
        when(mapper.commissionEvents(7L, 100)).thenReturn(List.of());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        var result = new AppTeamInsightsService(mapper, environment).commissions(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "").containsEntry("serverCanonical", true)
                .doesNotContainKeys("factStatus", "payoutStatus");
        verify(mapper).commissionEvents(7L, 100);
    }

}
