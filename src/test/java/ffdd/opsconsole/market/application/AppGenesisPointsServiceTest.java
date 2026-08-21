package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.mapper.AppGenesisPointsMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class AppGenesisPointsServiceTest {
    private final AppGenesisPointsMapper mapper = org.mockito.Mockito.mock(AppGenesisPointsMapper.class);
    private final Environment environment = org.mockito.Mockito.mock(Environment.class);
    private final AppGenesisPointsService service = new AppGenesisPointsService(mapper, environment);

    @Test
    void ranks_only_holders_in_the_authenticated_environment_and_projects_current_user() {
        when(mapper.userScope(7L)).thenReturn(new AppGenesisPointsMapper.UserScope(1, "sandbox-user"));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "")).thenReturn("acceptance-20260815");
        when(mapper.sandboxLeaderboard("acceptance-20260815")).thenReturn(List.of(
                new AppGenesisPointsMapper.PointsRow(7L, "Alice", 3L),
                new AppGenesisPointsMapper.PointsRow(8L, "Bob", 1L)));
        when(mapper.sandboxCurrentUser(7L,"acceptance-20260815"))
                .thenReturn(new AppGenesisPointsMapper.PointsRow(7L,"Alice",3L));
        when(mapper.sandboxCurrentRank(7L,"acceptance-20260815")).thenReturn(1);

        var result = service.projection(7L);

        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX");
        assertThat(result.getData()).containsEntry("source", "mock");
        assertThat(result.getData().get("leaderboard").toString()).contains("points=3000");
        assertThat(result.getData().get("currentUser").toString()).contains("rank=1");
    }

    @Test
    void refuses_a_production_user_in_a_sandbox_profile() {
        when(mapper.userScope(7L)).thenReturn(new AppGenesisPointsMapper.UserScope(0, "production-user"));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        assertThatThrownBy(() -> service.projection(7L))
                .hasMessage("GENESIS_SANDBOX_USER_REQUIRED");
    }

    @Test
    void keeps_current_user_points_when_user_is_outside_top_100() {
        when(mapper.userScope(7L)).thenReturn(new AppGenesisPointsMapper.UserScope(0, "production-user"));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.leaderboard(0)).thenReturn(List.of());
        when(mapper.currentUser(7L, 0)).thenReturn(new AppGenesisPointsMapper.PointsRow(7L, "Alice", 2L));

        var result = service.projection(7L);

        assertThat(result.getData().get("currentUser").toString()).contains("rank=null", "points=2000", "holdings=2");
    }
}
