package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class AppTeamInsightsServiceTest {
    private AppTeamInsightsService service(AppTeamInsightsMapper mapper, MockEnvironment environment) {
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        return new AppTeamInsightsService(mapper, mock(LeadershipPoolConfigGuard.class), config, environment);
    }

    private AppTeamInsightsService service(AppTeamInsightsMapper mapper, MockEnvironment environment,
                                           LeadershipPoolConfigGuard poolConfig) {
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        return new AppTeamInsightsService(mapper, poolConfig, config, environment);
    }
    @Test
    void leadershipRankDistributionGroupsByTheSelectedSourceColumnForOnlyFullGroupBy() throws Exception {
        var method = AppTeamInsightsMapper.class.getMethod("rankDistribution", Integer.class, int.class);
        var select = method.getAnnotation(org.apache.ibatis.annotations.Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        assertThat(sql).contains("GROUP BY u.v_rank")
                .doesNotContain("GROUP BY UPPER(u.v_rank)");
    }

    @Test
    void appReadModelsAcceptExplicitSettlementWindowsRatherThanMysqlSessionDates() throws Exception {
        var leaderboardMethod = AppTeamInsightsMapper.class.getMethod("leaderboardEligible", String.class, Integer.class,
                LocalDateTime.class, LocalDateTime.class, BigDecimal.class, int.class, LocalDateTime.class);
        var leaderboardSql = String.join(" ", leaderboardMethod.getAnnotation(org.apache.ibatis.annotations.Select.class).value())
                .replaceAll("\\s+", " ");
        var poolMethod = AppTeamInsightsMapper.class.getMethod("currentLeadershipPool", Integer.class,
                LocalDateTime.class, LocalDateTime.class);
        var poolSql = String.join(" ", poolMethod.getAnnotation(org.apache.ibatis.annotations.Select.class).value())
                .replaceAll("\\s+", " ");
        var commissionSummaryMethod = AppTeamInsightsMapper.class.getMethod("commissionSummary", Long.class,
                LocalDateTime.class);
        var commissionSummarySql = String.join(" ", commissionSummaryMethod.getAnnotation(org.apache.ibatis.annotations.Select.class).value())
                .replaceAll("\\s+", " ");
        var commissionBucketsMethod = AppTeamInsightsMapper.class.getMethod("commissionBuckets", Long.class,
                LocalDateTime.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class,
                LocalDateTime.class);
        var commissionBucketsSql = String.join(" ", commissionBucketsMethod.getAnnotation(org.apache.ibatis.annotations.Select.class).value())
                .replaceAll("\\s+", " ");
        var unilevelEventsMethod = AppTeamInsightsMapper.class.getMethod("unilevelEvents", Long.class, Integer.class,
                LocalDateTime.class, LocalDateTime.class, LocalDateTime.class, long.class, long.class);
        var unilevelEventsSql = String.join(" ", unilevelEventsMethod.getAnnotation(org.apache.ibatis.annotations.Select.class).value())
                .replaceAll("\\s+", " ");

        assertThat(leaderboardSql).contains("UPPER(ce.status)='UNLOCKED'", "#{fromInclusive}", "#{toExclusive}",
                "#{minVolumeUsd}", "'FRAUD','DISQUALIFIED','RISK'")
                .doesNotContain("CURDATE()", "NOW()");
        assertThat(poolSql).contains("#{fromInclusive}", "#{toExclusive}")
                .doesNotContain("CURDATE()", "NOW()");
        assertThat(commissionSummarySql).contains("ce.created_at <= #{snapshotAt}");
        assertThat(commissionBucketsSql).contains("ce.created_at >= #{monthFrom}", "ce.created_at < #{monthTo}",
                "ce.created_at >= #{todayFrom}", "ce.created_at < #{todayTo}",
                "ce.created_at <= #{snapshotAt}", "GROUP BY ce.commission_type, ce.status")
                .doesNotContain("CURDATE()", "NOW()");
        assertThat(unilevelEventsSql).contains("#{snapshotAt}", "#{fromInclusive}", "#{toExclusive}")
                .doesNotContain("CURDATE()", "NOW()");
    }

    @Test
    void historicalLeaderboardUsesTheSnapshotBusinessDateForItsPeriodWindow() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.leaderboardEligible(eq("week"), eq(0), any(), any(), eq(BigDecimal.ZERO), eq(50), any()))
                .thenReturn(List.of());

        service(mapper, new MockEnvironment()).leaderboard(
                7L, "week", 1, 20, "2026-08-30T15:59:59Z");

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).leaderboardEligible(eq("week"), eq(0), from.capture(), to.capture(),
                eq(BigDecimal.ZERO), eq(50), eq(LocalDateTime.of(2026, 8, 30, 23, 59, 59)));
        assertThat(from.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 24, 0, 0));
        assertThat(to.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 31, 0, 0));
    }

    @Test
    void leaderboardContinuationUsesTheFrozenCandidateSnapshotRatherThanRequeryingLiveRanks() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        var frozen = List.of(
                new AppTeamInsightsMapper.LeaderboardRow(1, 7L, "Alice", "V5", new BigDecimal("30"), 2, 8, 1),
                new AppTeamInsightsMapper.LeaderboardRow(2, 8L, "Bob", "V4", new BigDecimal("20"), 1, 3, 0));
        when(mapper.leaderboardEligible(eq("week"), eq(0), any(), any(), eq(BigDecimal.ZERO), eq(50), any()))
                .thenReturn(frozen);
        var service = service(mapper, new MockEnvironment());

        var firstPage = service.leaderboard(7L, "week", 1, 1, "2026-08-30T15:59:59Z", null);
        String snapshotVersion = String.valueOf(firstPage.getData().get("snapshotVersion"));
        var secondPage = service.leaderboard(7L, "week", 2, 1, "2026-08-30T15:59:59Z", snapshotVersion);

        assertThat(firstPage.getCode()).isZero();
        assertThat(snapshotVersion).matches("[a-f0-9]{64}");
        assertThat(secondPage.getCode()).isZero();
        assertThat(secondPage.getData().get("rows").toString()).contains("rank=2", "earnedUSDT=20");
        verify(mapper, times(1)).leaderboardEligible(eq("week"), eq(0), any(), any(), eq(BigDecimal.ZERO), eq(50), any());
    }

    @Test
    void leaderboardContinuationRejectsAnUnknownOrExpiredCandidateSnapshot() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));

        assertThatThrownBy(() -> service(mapper, new MockEnvironment()).leaderboard(
                7L, "week", 2, 20, "2026-08-30T15:59:59Z", "a".repeat(64)))
                .isInstanceOf(BizException.class)
                .hasMessage("TEAM_LEADERBOARD_SNAPSHOT_STALE");
        verify(mapper).userScope(7L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void productionLeaderboardAndCommissionAreSelfScopedAndServerBacked() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.leaderboardEligible(eq("week"), eq(0), any(), any(), eq(BigDecimal.ZERO), eq(50), any(LocalDateTime.class))).thenReturn(List.of(new AppTeamInsightsMapper.LeaderboardRow(
                1, 7L, "Alice", "V5", new BigDecimal("42.5"), 3, 9, 1)));
        when(mapper.commissionEventCount(eq(7L), any(LocalDateTime.class))).thenReturn(1L);
        when(mapper.commissionEvents(eq(7L), any(LocalDateTime.class), eq(0L), eq(20L))).thenReturn(List.of(new AppTeamInsightsMapper.CommissionRow(
                11L, "DIRECT", 8L, "Bob", 1, "ORD-1", new BigDecimal("99"),
                new BigDecimal("9.9"), BigDecimal.ZERO, "UNLOCKED", LocalDateTime.now(), LocalDateTime.now())));
        when(mapper.commissionBuckets(eq(7L), any(), any(), any(), any(), any())).thenReturn(List.of());
        var service = service(mapper, new MockEnvironment());

        var board = service.leaderboard(7L, "week");
        var commissions = service.commissions(7L);

        assertThat(board.getCode()).isZero();
        assertThat(board.getData()).containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat(commissions.getCode()).isZero();
        verify(mapper).leaderboardEligible(eq("week"), eq(0), any(), any(), eq(BigDecimal.ZERO), eq(50), any(LocalDateTime.class));
        verify(mapper).commissionEvents(eq(7L), any(LocalDateTime.class), eq(0L), eq(20L));
    }

    @Test
    void isolatedProfileRejectsProductionIdentityBeforeProjectionRead() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> service(mapper, environment).leaderboard(7L, "week"))
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

        var result = service(mapper, environment).commissions(7L);

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
        var service = service(mapper, environment);

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

        var result = service(mapper, environment)
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

        var result = service(mapper, environment)
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

        assertThatThrownBy(() -> service(mapper, environment).commissions(7L))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("TEAM_RUN_ID_REQUIRED");
    }

    @Test
    void sandboxInsightsRejectProductionIdentityBeforeGeneratingFacts() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> service(mapper, environment)
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
        when(mapper.unilevelEventCount(eq(7L), eq(0), any(LocalDateTime.class), any(), any())).thenReturn(2L);
        when(mapper.unilevelEvents(eq(7L), eq(0), any(LocalDateTime.class), any(), any(), eq(0L), eq(20L))).thenReturn(List.of(
                new AppTeamInsightsMapper.UnilevelRow(21L, 8L, "Bob", 1, "ORD-1", "2026-W33",
                        new BigDecimal("99"), new BigDecimal("9.9"), new BigDecimal("50"), "USDT",
                        "COOLING", LocalDateTime.of(2026, 8, 13, 0, 0), LocalDateTime.of(2026, 9, 12, 0, 0)),
                new AppTeamInsightsMapper.UnilevelRow(22L, 8L, "Bob", 1, "ORD-1", "2026-W33",
                        new BigDecimal("99"), BigDecimal.ZERO, new BigDecimal("495"), "NEX",
                        "COOLING", LocalDateTime.of(2026, 8, 13, 0, 0), LocalDateTime.of(2026, 9, 12, 0, 0))));
        when(mapper.unilevelSplit(eq(7L), eq(0), any(LocalDateTime.class), any(), any())).thenReturn(new AppTeamInsightsMapper.UnilevelSplitRow(
                new BigDecimal("9.9"), new BigDecimal("495"), 2,
                BigDecimal.ZERO, BigDecimal.ZERO, 0));

        var result = service(mapper, new MockEnvironment()).unilevel(7L, "week");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "server").containsEntry("period", "week");
        assertThat(result.getData().get("events").toString()).contains("2026-W33", "layer")
                .doesNotContain("sourceUserId");
        assertThat(result.getData().get("split").toString()).contains("direct", "extended", "amountUSDT", "amountNEX");
        verify(mapper).unilevelEvents(eq(7L), eq(0), any(LocalDateTime.class), any(), any(), eq(0L), eq(20L));
    }

    @Test
    void developmentAllowsAnyActiveDevelopmentAccountAndReadsCanonicalCommissionFacts() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.commissionEvents(eq(7L), any(LocalDateTime.class), eq(0L), eq(20L))).thenReturn(List.of());
        when(mapper.commissionBuckets(eq(7L), any(), any(), any(), any(), any())).thenReturn(List.of());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        var result = service(mapper, environment).commissions(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "").containsEntry("serverCanonical", true)
                .doesNotContainKeys("factStatus", "payoutStatus");
        verify(mapper).commissionEvents(eq(7L), any(LocalDateTime.class), eq(0L), eq(20L));
    }

    @Test
    void paidAndSettledCommissionEventsRemainWithdrawnAndCannotBeWithdrawnAgain() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 8, 0);
        when(mapper.commissionEvents(eq(7L), any(LocalDateTime.class), eq(0L), eq(20L))).thenReturn(List.of(
                new AppTeamInsightsMapper.CommissionRow(11L, "DIRECT", 8L, "Bob", 1, "ORD-1",
                        new BigDecimal("99"), new BigDecimal("9.9"), BigDecimal.ZERO,
                        "PAID", createdAt, createdAt),
                new AppTeamInsightsMapper.CommissionRow(12L, "BINARY", 9L, "Alice", 2, "ORD-2",
                        new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO,
                        "SETTLED", createdAt, createdAt)));
        when(mapper.commissionBuckets(eq(7L), any(), any(), any(), any(), any())).thenReturn(List.of());

        var result = service(mapper, new MockEnvironment()).commissions(7L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) result.getData().get("events");
        assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event)
                .containsEntry("status", "withdrawn")
                .containsEntry("withdrawable", false)
                .containsEntry("ts", java.time.Instant.parse("2026-09-01T00:00:00Z").toEpochMilli())
                .containsEntry("unlockAt", java.time.Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unilevelPaginatesDetailsAndUsesTheFullServerAggregate() throws Exception {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        var rows = java.util.stream.LongStream.rangeClosed(1, 501).mapToObj(id ->
                new AppTeamInsightsMapper.UnilevelRow(id, 8L, "Member", id == 501 ? 2 : 1,
                        "ORD-" + id, "2026-W35", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        "USDT", "COOLING", LocalDateTime.of(2026, 8, 31, 0, 0), null)).toList();
        when(mapper.unilevelEventCount(eq(7L), eq(0), any(LocalDateTime.class), any(), any())).thenReturn(501L);
        when(mapper.unilevelEvents(eq(7L), eq(0), any(LocalDateTime.class), any(), any(), eq(500L), eq(1L))).thenReturn(List.of(rows.get(500)));
        when(mapper.unilevelSplit(eq(7L), eq(0), any(LocalDateTime.class), any(), any())).thenReturn(new AppTeamInsightsMapper.UnilevelSplitRow(
                new BigDecimal("500"), BigDecimal.ZERO, 500,
                BigDecimal.ONE, BigDecimal.ZERO, 1));
        var result = service(mapper, new MockEnvironment()).unilevel(7L, "month", 501, 1).getData();
        assertThat((List<?>) result.get("events")).hasSize(1);
        assertThat(result).containsEntry("page", 501L).containsEntry("pageSize", 1L).containsEntry("totalRows", 501L);
        var split = (Map<String, Map<String, Object>>) result.get("split");
        assertThat(split.get("direct")).containsEntry("count", 500);
        assertThat((BigDecimal) split.get("direct").get("amountUSDT")).isEqualByComparingTo("500");
        assertThat((BigDecimal) split.get("extended").get("amountUSDT")).isEqualByComparingTo("1");
        verify(mapper).unilevelSplit(eq(7L), eq(0), any(LocalDateTime.class), any(), any());
        String query = String.join(" ", AppTeamInsightsMapper.class
                .getMethod("unilevelEvents", Long.class, Integer.class, LocalDateTime.class,
                        LocalDateTime.class, LocalDateTime.class, long.class, long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertThat(query.toUpperCase(java.util.Locale.ROOT)).contains("LIMIT #{OFFSET},#{LIMIT}");
    }

    @Test
    void leaderboardPoolReadsTheConfiguredF4PeriodPrizeInsteadOfReturningAClientSeed() {
        var mapper = mock(AppTeamInsightsMapper.class);
        var config = mock(PlatformConfigFacade.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.leaderboardEligible(eq("week"), eq(0), any(), any(), eq(BigDecimal.ZERO), eq(50), any(LocalDateTime.class))).thenReturn(List.of());
        when(config.activeValue("team.ui.F.pool.periodPrize"))
                .thenReturn(Optional.of("{\"today\":1000,\"week\":50000,\"month\":200000,\"allTime\":1000000}"));
        var service = new AppTeamInsightsService(mapper, mock(LeadershipPoolConfigGuard.class), config, new MockEnvironment());

        var board = service.leaderboard(7L, "week");

        assertThat(board.getData()).containsEntry("poolUsd", new BigDecimal("50000"));
        verify(config).activeValue("team.ui.F.pool.periodPrize");
    }

    @Test
    void weeklyLeaderboardUsesF4OverrideAndSettlementEligibleCandidates() {
        var mapper = mock(AppTeamInsightsMapper.class);
        var config = mock(PlatformConfigFacade.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(config.activeValue("team.ui.F.leaderboard.poolUsd")).thenReturn(Optional.of("100"));
        when(config.activeValue("team.ui.F.leaderboard.minUsd")).thenReturn(Optional.of("25"));
        when(mapper.leaderboardEligible(eq("week"), eq(0), any(), any(), eq(new BigDecimal("25")), eq(50), any(LocalDateTime.class)))
                .thenReturn(List.of(new AppTeamInsightsMapper.LeaderboardRow(
                        1, 7L, "Alice", "V5", new BigDecimal("30"), 1, 1, 1)));
        var service = new AppTeamInsightsService(mapper, mock(LeadershipPoolConfigGuard.class), config, new MockEnvironment());

        var board = service.leaderboard(7L, "week");

        assertThat(board.getData()).containsEntry("poolUsd", new BigDecimal("100"))
                .containsEntry("topN", 1);
        verify(mapper).leaderboardEligible(eq("week"), eq(0), any(), any(), eq(new BigDecimal("25")), eq(50), any(LocalDateTime.class));
    }

    @Test
    void pausedLeaderboardDoesNotProjectEligibleRanksOrAPrizePool() {
        var mapper = mock(AppTeamInsightsMapper.class);
        var config = mock(PlatformConfigFacade.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(config.activeValue("team.ui.F.leaderboard.paused")).thenReturn(Optional.of("on"));
        var service = new AppTeamInsightsService(mapper, mock(LeadershipPoolConfigGuard.class), config, new MockEnvironment());

        var board = service.leaderboard(7L, "week");

        assertThat(board.getData()).containsEntry("poolUsd", BigDecimal.ZERO).containsEntry("topN", 0);
        verify(mapper).userScope(7L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void commissionAggregateIsServerWideRatherThanDerivedFromTheRecentHundredRows() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.commissionEvents(eq(7L), any(LocalDateTime.class), eq(0L), eq(20L))).thenReturn(List.of());
        when(mapper.commissionSummary(eq(7L), any(LocalDateTime.class))).thenReturn(new AppTeamInsightsMapper.CommissionSummaryRow(
                new BigDecimal("150"), new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("120"), 4));
        when(mapper.commissionBuckets(eq(7L), any(), any(), any(), any(), any())).thenReturn(List.of(
                new AppTeamInsightsMapper.CommissionBucket("DIRECT", "UNLOCKED", 100,
                        new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("2"), null),
                new AppTeamInsightsMapper.CommissionBucket("BINARY", "COOLING", 1,
                        new BigDecimal("120"), new BigDecimal("10"), new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("3"),
                        LocalDateTime.of(2026, 9, 1, 0, 0))));

        var result = service(mapper, new MockEnvironment()).commissions(7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> aggregate = (Map<String, Object>) result.getData().get("aggregate");
        assertThat(aggregate)
                .containsEntry("totalUSDT", new BigDecimal("150")).containsEntry("totalNEX", new BigDecimal("20"))
                .containsEntry("directUSDT", new BigDecimal("30")).containsEntry("extendedUSDT", new BigDecimal("120"))
                .containsEntry("contributorCount", 4).containsEntry("monthUSDT", new BigDecimal("30"))
                .containsEntry("monthNEX", new BigDecimal("5")).containsEntry("todayUSDT", new BigDecimal("5"))
                .containsEntry("unlockedUSDT", new BigDecimal("30")).containsEntry("unlockedNEX", new BigDecimal("10"))
                .containsEntry("coolingUSDT", new BigDecimal("120")).containsEntry("eventCount", 101);
        verify(mapper).commissionSummary(eq(7L), any(LocalDateTime.class));
    }

    @Test
    void commissionBucketsUseFullNaturalMonthAndTodayWindowsAtTheirBoundaries() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.commissionEvents(eq(7L), any(LocalDateTime.class), eq(0L), eq(20L))).thenReturn(List.of());
        when(mapper.commissionBuckets(eq(7L), any(), any(), any(), any(), any())).thenReturn(List.of());

        service(mapper, new MockEnvironment()).commissions(7L);

        ArgumentCaptor<LocalDateTime> fromMonth = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toMonth = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> fromToday = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toToday = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).commissionBuckets(eq(7L), fromMonth.capture(), toMonth.capture(),
                fromToday.capture(), toToday.capture(), any(LocalDateTime.class));

        assertThat(fromMonth.getValue()).isEqualTo(fromMonth.getValue().toLocalDate().atStartOfDay());
        assertThat(fromMonth.getValue().getDayOfMonth()).isEqualTo(1);
        assertThat(toMonth.getValue()).isEqualTo(fromMonth.getValue().plusMonths(1));
        assertThat(fromToday.getValue()).isEqualTo(fromToday.getValue().toLocalDate().atStartOfDay());
        assertThat(toToday.getValue()).isEqualTo(fromToday.getValue().plusDays(1));
        assertThat(fromToday.getValue()).isBetween(fromMonth.getValue(), toMonth.getValue().minusNanos(1));
    }

    @Test
    void historicalCommissionAggregateUsesOneSnapshotCutoffAndItsBusinessDayWindows() {
        var mapper = mock(AppTeamInsightsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.commissionEvents(eq(7L), any(), eq(0L), eq(20L))).thenReturn(List.of());
        when(mapper.commissionBuckets(eq(7L), any(), any(), any(), any(), any())).thenReturn(List.of());

        service(mapper, new MockEnvironment()).commissions(
                7L, 1, 20, "2026-08-30T15:59:59Z");

        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 30, 23, 59, 59);
        verify(mapper).commissionSummary(7L, cutoff);
        verify(mapper).commissionBuckets(7L,
                LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 8, 30, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0), cutoff);
    }

    @Test
    void commissionProjectionUsesOneReadOnlyRepeatableReadTransaction() throws Exception {
        Transactional transaction = AppTeamInsightsService.class.getMethod("commissions", Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void leadershipPoolUsesTheGuardedThresholdRateAndCronForItsReadProjection() {
        var mapper = mock(AppTeamInsightsMapper.class);
        var poolConfig = mock(LeadershipPoolConfigGuard.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(poolConfig.requireValid()).thenReturn(new LeadershipPoolConfigGuard.SettlementConfig(
                7L, new BigDecimal("0.075"), 5, new BigDecimal("50000"), "0 0 0 * * *", "fingerprint"));
        when(mapper.rankDistribution(0, 5)).thenReturn(List.of(
                new AppTeamInsightsMapper.RankDistributionRow(5, 2, 4)));
        when(mapper.leadershipPoolSummary()).thenReturn(Map.of(
                "weeklyGmvUsd", new BigDecimal("10000"),
                "monthLeadershipUsd", new BigDecimal("49500"),
                "weeklySettledCount", 0,
                "weeklyInjectedUsd", new BigDecimal("1000")));
        when(mapper.leadershipHistory(eq(7L), any())).thenReturn(List.of());

        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue("team.ui.F.vrank.leadership.topN")).thenReturn(Optional.of("3"));
        var result = new AppTeamInsightsService(mapper, poolConfig, config, new MockEnvironment()).leadershipPool(7L);

        assertThat(result.getData()).containsEntry("unlockRank", 5)
                .containsEntry("injectRate", new BigDecimal("0.075"))
                .containsEntry("topN", 3);
        assertThat((BigDecimal) result.getData().get("currentWeekPoolUSDT"))
                .isEqualByComparingTo("500");
        assertThat((BigDecimal) result.getData().get("projectedPayoutUSDT"))
                .isEqualByComparingTo("250");
        verify(mapper).leadershipPoolSummary();
        assertThat(result.getData().get("nextPayoutAt")).isInstanceOf(String.class);
        verify(mapper).rankDistribution(0, 5);
    }

    @Test
    void leadershipPoolReturnsAnExplicitHoldInsteadOfLeakingInvalidConfigAsGeneric500() {
        var mapper = mock(AppTeamInsightsMapper.class);
        var poolConfig = mock(LeadershipPoolConfigGuard.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(poolConfig.requireValid()).thenThrow(new LeadershipPoolConfigGuard.ConfigUnavailableException(
                LeadershipPoolConfigGuard.CRON_KEY, "INVALID_CRON", "fingerprint"));

        var result = service(mapper, new MockEnvironment(), poolConfig).leadershipPool(7L);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("F4_LEADERSHIP_POOL_HOLD");
        assertThat(result.getData()).containsEntry("available", false).containsEntry("state", "HOLD");
        verify(mapper).userScope(7L);
        verifyNoMoreInteractions(mapper);
    }

}
