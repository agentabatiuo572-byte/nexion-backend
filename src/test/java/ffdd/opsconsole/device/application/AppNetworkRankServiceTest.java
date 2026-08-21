package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppNetworkRankMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class AppNetworkRankServiceTest {
    private final AppNetworkRankMapper mapper = org.mockito.Mockito.mock(AppNetworkRankMapper.class);
    private final Environment environment = org.mockito.Mockito.mock(Environment.class);

    @Test
    void returns_server_current_rank_and_fail_closed_24h_delta() {
        when(mapper.userScope(7L)).thenReturn(new AppNetworkRankMapper.UserScope(0));
        when(mapper.rankedUsers()).thenReturn(List.of(
                new AppNetworkRankMapper.RankRow(8L, new BigDecimal("20")),
                new AppNetworkRankMapper.RankRow(7L, new BigDecimal("10"))));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        var result = new AppNetworkRankService(mapper, environment).snapshot(7L);

        assertThat(result.getData()).containsEntry("source", "nx_user_device")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("serverCanonical", true)
                .containsEntry("currentRank", 2)
                .containsEntry("rankChange24h", null)
                .containsEntry("snapshotAvailable", false);
    }

    @Test
    void returns_run_scoped_sandbox_rank_without_reading_production_rows() {
        when(mapper.userScope(7L)).thenReturn(new AppNetworkRankMapper.UserScope(1));
        when(mapper.rankedSandboxUsers("rank-run-20260819")).thenReturn(List.of(
                new AppNetworkRankMapper.RankRow(8L, new BigDecimal("20")),
                new AppNetworkRankMapper.RankRow(7L, new BigDecimal("10"))));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "")).thenReturn("rank-run-20260819");

        var result = new AppNetworkRankService(mapper, environment).snapshot(7L);

        assertThat(result.getData()).containsEntry("source", "nx_user_device")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "rank-run-20260819")
                .containsEntry("serverCanonical", true)
                .containsEntry("currentRank", 2)
                .containsEntry("rankChange24h", null)
                .containsEntry("snapshotAvailable", false);
        verify(mapper, never()).rankedUsers();
    }

    @Test
    void rejects_sandbox_profile_without_a_valid_run_before_user_or_rank_query() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "")).thenReturn("");

        assertThatThrownBy(() -> new AppNetworkRankService(mapper, environment).snapshot(7L))
                .hasMessage("NETWORK_RANK_SANDBOX_RUN_ID_REQUIRED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).rankedUsers();
    }

    @Test
    void rejects_legacy_unscoped_run_case_insensitively_before_user_or_rank_query() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "")).thenReturn("legacy_unscoped");

        assertThatThrownBy(() -> new AppNetworkRankService(mapper, environment).snapshot(7L))
                .hasMessage("NETWORK_RANK_SANDBOX_RUN_ID_REQUIRED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).rankedSandboxUsers("legacy_unscoped");
    }

    @Test
    void rejects_unknown_or_mixed_runtime_before_user_or_rank_query() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod", "staging"});

        assertThatThrownBy(() -> new AppNetworkRankService(mapper, environment).snapshot(7L))
                .hasMessage("NETWORK_RANK_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).rankedUsers();
    }

    @Test
    void development_user_reads_canonical_rank_without_acceptance_run() {
        when(mapper.userScope(7L)).thenReturn(new AppNetworkRankMapper.UserScope(1));
        when(mapper.rankedDevelopmentUsers()).thenReturn(List.of(
                new AppNetworkRankMapper.RankRow(8L, new BigDecimal("20")),
                new AppNetworkRankMapper.RankRow(7L, new BigDecimal("10"))));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        var result = new AppNetworkRankService(mapper, environment).snapshot(7L);

        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("currentRank", 2);
        verify(mapper, never()).rankedSandboxUsers(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejects_unknown_runtime_before_user_or_rank_query() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"staging"});

        assertThatThrownBy(() -> new AppNetworkRankService(mapper, environment).snapshot(7L))
                .hasMessage("NETWORK_RANK_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(7L);
        verify(mapper, never()).rankedUsers();
    }

    @Test
    void rejects_sandbox_user_in_production_before_rank_query() {
        when(mapper.userScope(7L)).thenReturn(new AppNetworkRankMapper.UserScope(1));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> new AppNetworkRankService(mapper, environment).snapshot(7L))
                .hasMessage("NETWORK_RANK_PRODUCTION_USER_REQUIRED");
        verify(mapper, never()).rankedUsers();
    }

    @Test
    void rejects_production_user_on_the_sandbox_rail_before_rank_query() {
        when(mapper.userScope(7L)).thenReturn(new AppNetworkRankMapper.UserScope(0));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "")).thenReturn("rank-run-20260819");

        assertThatThrownBy(() -> new AppNetworkRankService(mapper, environment).snapshot(7L))
                .hasMessage("NETWORK_RANK_SANDBOX_USER_REQUIRED");
        verify(mapper, never()).rankedSandboxUsers("rank-run-20260819");
    }
}
