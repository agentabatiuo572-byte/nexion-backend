package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppReferralRewardService;
import ffdd.opsconsole.growth.domain.AppReferralRewardView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.mapper.AppProofMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppProofServiceTest {
    @Test
    void sandboxFixtureIsRunScopedAndUsesTheSameHcmAndTieAwareMath() {
        var mapper = mock(AppProofMapper.class);
        when(mapper.user(7L)).thenReturn(new AppProofMapper.UserRow(
                LocalDateTime.of(2026, 1, 1, 0, 0), "NXAB12CD34EF"));
        var referral = mock(AppReferralRewardService.class);
        when(referral.snapshot(7L, 20)).thenReturn(ApiResult.ok(new AppReferralRewardView(
                "NXAB12CD34EF", BigDecimal.ONE, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), 20, "mock", "SANDBOX", "run-proof-20260816", List.of("fixture"), Instant.now())));
        var network = mock(AppTeamNetworkService.class);
        when(network.snapshot(7L)).thenReturn(ApiResult.ok(Map.of("totalMembers", 0, "activeMembers", 0)));
        var fixtures = new AppProofSandboxFixtureService();
        fixtures.put("run-proof-20260816", 7L, new AppProofSandboxFixtureService.Fixture(
                new BigDecimal("20"), 3, 8, java.time.LocalDate.of(2026, 8, 17), 2L, 10L));

        var result = new AppProofService(mapper, referral, network, fixtures,
                Clock.fixed(Instant.parse("2026-08-17T17:00:00Z"), ZoneOffset.UTC)).snapshot(7L);

        assertThat(result.getData()).containsEntry("earningsTotalUsdt", new BigDecimal("20"))
                .containsEntry("serverCanonical", true)
                .containsEntry("asOf", "2026-08-18")
                .containsEntry("currentStreak", 3L).containsEntry("longestStreak", 8L)
                .containsEntry("topPercentile", new BigDecimal("20.0"));
        verify(mapper, never()).sandboxEarningsTotalUsdt("run-proof-20260816", 7L);
    }

    @Test
    void returnsServerSnapshotWithUnavailableFactsWhenSandboxHasNoFixtureAndTeamProjectionFails() {
        var mapper = mock(AppProofMapper.class);
        when(mapper.user(7L)).thenReturn(new AppProofMapper.UserRow(
                LocalDateTime.of(2026, 1, 1, 0, 0), "NXAB12CD34EF"));
        when(mapper.sandboxEarningsTotalUsdt("run-proof-20260816", 7L)).thenReturn(null);
        var referral = mock(AppReferralRewardService.class);
        when(referral.snapshot(7L, 20)).thenReturn(ApiResult.ok(new AppReferralRewardView(
                "NXAB12CD34EF", BigDecimal.ONE, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), 20, "mock", "SANDBOX", "run-proof-20260816", List.of("sandbox"), Instant.now())));
        var network = mock(AppTeamNetworkService.class);
        when(network.snapshot(7L)).thenThrow(new ffdd.opsconsole.shared.exception.BizException(503, "TEAM_NETWORK_SANDBOX_FACTS_UNAVAILABLE"));

        var result = new AppProofService(mapper, referral, network,
                Clock.fixed(Instant.parse("2026-08-17T05:00:00Z"), ZoneOffset.UTC)).snapshot(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("earningsTotalUsdt", null)
                .containsEntry("currentStreak", null).containsEntry("longestStreak", null)
                .containsEntry("topPercentile", null).containsEntry("onlineDevices", null);
        var unavailableTeam = new java.util.LinkedHashMap<String, Object>();
        unavailableTeam.put("totalMembers", null);
        unavailableTeam.put("activeMembers", null);
        assertThat(result.getData().get("team")).isEqualTo(unavailableTeam);
        assertThat(result.getData().get("availability").toString()).contains("UNAVAILABLE");
    }
    @Test
    void usesReferralAndTeamServerSnapshotsWithoutClientPercentileHeuristic() {
        var mapper = mock(AppProofMapper.class);
        when(mapper.user(7L)).thenReturn(new AppProofMapper.UserRow(
                LocalDateTime.of(2026, 1, 1, 0, 0), "NXAB12CD34EF"));
        when(mapper.onlineDevices(7L)).thenReturn(2L);
        when(mapper.earningsTotalUsdt(7L)).thenReturn(new BigDecimal("123.45"));
        when(mapper.streak(7L)).thenReturn(new AppProofMapper.StreakRow(3, 7, java.time.LocalDate.of(2026, 8, 17)));
        when(mapper.earningsPopulation(7L, new BigDecimal("123.45")))
                .thenReturn(new AppProofMapper.PercentileRow(2L, 10L));
        var referral = mock(AppReferralRewardService.class);
        when(referral.snapshot(7L, 20)).thenReturn(ApiResult.ok(new AppReferralRewardView(
                "NXAB12CD34EF", BigDecimal.ONE, 4, 0, 2, new BigDecimal("50"), BigDecimal.ZERO,
                List.of(), 20, "ledger", "PRODUCTION", null, List.of("nx_wallet_ledger"), Instant.now())));
        var network = mock(AppTeamNetworkService.class);
        when(network.snapshot(7L)).thenReturn(ApiResult.ok(Map.of("totalMembers", 9, "activeMembers", 6)));

        var result = new AppProofService(mapper, referral, network,
                Clock.fixed(Instant.parse("2026-08-17T05:00:00Z"), ZoneOffset.UTC)).snapshot(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("earningsTotalUsdt", new BigDecimal("123.45"))
                .containsEntry("currentStreak", 3L)
                .containsEntry("longestStreak", 7L)
                .containsEntry("topPercentile", new BigDecimal("20.0"))
                .containsKey("serverTime").containsKey("asOf").containsKey("provenance");
        assertThat(result.getData()).doesNotContainKey("topPct");
    }

    @Test
    void staleCheckInResetsCurrentButKeepsLongestAndNoPopulationMeansUnavailable() {
        var mapper = mock(AppProofMapper.class);
        when(mapper.user(7L)).thenReturn(new AppProofMapper.UserRow(
                LocalDateTime.of(2026, 1, 1, 0, 0), "NXAB12CD34EF"));
        when(mapper.onlineDevices(7L)).thenReturn(0L);
        when(mapper.earningsTotalUsdt(7L)).thenReturn(new BigDecimal("10"));
        when(mapper.streak(7L)).thenReturn(new AppProofMapper.StreakRow(5, 12, java.time.LocalDate.of(2026, 8, 14)));
        when(mapper.earningsPopulation(7L, new BigDecimal("10")))
                .thenReturn(new AppProofMapper.PercentileRow(2L, 4L));
        var referral = mock(AppReferralRewardService.class);
        when(referral.snapshot(7L, 20)).thenReturn(ApiResult.ok(new AppReferralRewardView(
                "NXAB12CD34EF", BigDecimal.ONE, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), 20, "ledger", "PRODUCTION", null, List.of("nx_wallet_ledger"), Instant.now())));
        var network = mock(AppTeamNetworkService.class);
        when(network.snapshot(7L)).thenReturn(ApiResult.ok(Map.of("totalMembers", 0, "activeMembers", 0)));

        var result = new AppProofService(mapper, referral, network,
                Clock.fixed(Instant.parse("2026-08-17T05:00:00Z"), ZoneOffset.UTC)).snapshot(7L);

        assertThat(result.getData()).containsEntry("currentStreak", 0L)
                .containsEntry("longestStreak", 12L)
                .containsEntry("topPercentile", null);
    }

    @Test
    void checkInAtBusinessMidnightUsesHoChiMinhDateNotJvmOrClientTimezone() {
        var mapper = mock(AppProofMapper.class);
        when(mapper.user(7L)).thenReturn(new AppProofMapper.UserRow(
                LocalDateTime.of(2026, 1, 1, 0, 0), "NXAB12CD34EF"));
        when(mapper.onlineDevices(7L)).thenReturn(0L);
        when(mapper.earningsTotalUsdt(7L)).thenReturn(BigDecimal.ZERO);
        when(mapper.streak(7L)).thenReturn(new AppProofMapper.StreakRow(2, 2, java.time.LocalDate.of(2026, 8, 16)));
        when(mapper.earningsPopulation(7L, BigDecimal.ZERO)).thenReturn(new AppProofMapper.PercentileRow(0L, 0L));
        var referral = mock(AppReferralRewardService.class);
        when(referral.snapshot(7L, 20)).thenReturn(ApiResult.ok(new AppReferralRewardView(
                "NXAB12CD34EF", BigDecimal.ONE, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), 20, "ledger", "PRODUCTION", null, List.of("nx_wallet_ledger"), Instant.now())));
        var network = mock(AppTeamNetworkService.class);
        when(network.snapshot(7L)).thenReturn(ApiResult.ok(Map.of("totalMembers", 0, "activeMembers", 0)));

        var result = new AppProofService(mapper, referral, network,
                // 17:00Z is exactly 00:00 of the next HCM business date.
                Clock.fixed(Instant.parse("2026-08-16T17:00:00Z"), ZoneOffset.UTC)).snapshot(7L);

        assertThat(result.getData()).containsEntry("asOf", "2026-08-17")
                .containsEntry("currentStreak", 2L);
    }

    @Test
    void sandboxProofDoesNotReadCanonicalDeviceTableWithoutRunScopedDeviceFacts() {
        var mapper = mock(AppProofMapper.class);
        when(mapper.user(7L)).thenReturn(new AppProofMapper.UserRow(
                LocalDateTime.of(2026, 1, 1, 0, 0), "NXAB12CD34EF"));
        var referral = mock(AppReferralRewardService.class);
        when(referral.snapshot(7L, 20)).thenReturn(ApiResult.ok(new AppReferralRewardView(
                "NXAB12CD34EF", BigDecimal.ONE, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), 20, "mock", "SANDBOX", "run-proof-20260816", List.of(), Instant.now())));
        when(mapper.sandboxEarningsTotalUsdt("run-proof-20260816", 7L)).thenReturn(BigDecimal.ZERO);
        var network = mock(AppTeamNetworkService.class);
        when(network.snapshot(7L)).thenReturn(ApiResult.ok(Map.of("totalMembers", 0, "activeMembers", 0)));

        var result = new AppProofService(mapper, referral, network).snapshot(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("onlineDevices", null);
        assertThat(result.getData()).containsEntry("currentStreak", null)
                .containsEntry("longestStreak", null).containsEntry("topPercentile", null);
        verify(mapper, never()).onlineDevices(7L);
        verify(mapper, never()).streak(7L);
        verify(mapper, never()).earningsPopulation(7L, BigDecimal.ZERO);
    }
}
