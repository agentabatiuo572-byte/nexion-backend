package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(mapper.userScope(7L)).thenReturn(new AppNetworkRankMapper.UserScope(1));
        when(mapper.rankedUsers(1)).thenReturn(List.of(
                new AppNetworkRankMapper.RankRow(8L, new BigDecimal("20")),
                new AppNetworkRankMapper.RankRow(7L, new BigDecimal("10"))));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"acceptance"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "")).thenReturn("acceptance-20260815");

        var result = new AppNetworkRankService(mapper, environment).snapshot(7L);

        assertThat(result.getData()).containsEntry("source", "nx_user_device")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("currentRank", 2)
                .containsEntry("rankChange24h", null)
                .containsEntry("snapshotAvailable", false);
    }

    @Test
    void rejects_production_identity_in_sandbox_profile_before_rank_query() {
        when(mapper.userScope(7L)).thenReturn(new AppNetworkRankMapper.UserScope(0));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"acceptance"});

        assertThatThrownBy(() -> new AppNetworkRankService(mapper, environment).snapshot(7L))
                .hasMessage("NETWORK_RANK_SANDBOX_USER_REQUIRED");
    }
}
