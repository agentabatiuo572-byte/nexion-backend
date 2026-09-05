package ffdd.opsconsole.team.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.infrastructure.MybatisTeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class VRankProjectionClosureTest {

    @Test
    void successfulCanonicalUpdateSynchronizesUserAndAncestorProjections() {
        TeamCommissionMapper mapper = mock(TeamCommissionMapper.class);
        when(mapper.updateMemberVRank(42L, "V2")).thenReturn(1);
        MybatisTeamCommissionRepository repository = new MybatisTeamCommissionRepository(mapper);

        assertThat(repository.updateMemberVRank(42L, "V2")).isTrue();

        InOrder order = inOrder(mapper);
        order.verify(mapper).updateMemberVRank(42L, "V2");
        order.verify(mapper).syncUserVRank(42L, "V2");
        order.verify(mapper).syncMemberVRankProjections(42L, "V2");
    }

    @Test
    void missingCanonicalRowDoesNotCreateProjectionOnlyRank() {
        TeamCommissionMapper mapper = mock(TeamCommissionMapper.class);
        when(mapper.updateMemberVRank(42L, "V1")).thenReturn(0);
        MybatisTeamCommissionRepository repository = new MybatisTeamCommissionRepository(mapper);

        assertThat(repository.updateMemberVRank(42L, "V1")).isFalse();
        verify(mapper, never()).syncUserVRank(42L, "V1");
        verify(mapper, never()).syncMemberVRankProjections(42L, "V1");
    }

    @Test
    void nonMonetaryF1RewardsKeepTheirMissingAmountThroughTheReadProjection() {
        TeamCommissionMapper mapper = mock(TeamCommissionMapper.class);
        Map<String, Object> voucher = new LinkedHashMap<>();
        voucher.put("rewardId", "voucher-1");
        voucher.put("rankCode", "V3");
        voucher.put("rewardType", "voucher");
        voucher.put("amount", null);
        voucher.put("voucherId", "WELCOME-10");
        voucher.put("sortOrder", 1);
        when(mapper.selectVRankRewardRulesByRank("V3")).thenReturn(List.of(voucher));

        var rewards = new MybatisTeamCommissionRepository(mapper).selectVRankRewardRulesByRank("V3");

        assertThat(rewards).singleElement().satisfies(reward -> {
            assertThat(reward.rewardType()).isEqualTo("voucher");
            assertThat(reward.amount()).isNull();
            assertThat(reward.voucherId()).isEqualTo("WELCOME-10");
        });
    }

    @Test
    void migrationRepairsBothServerProjections() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260829_vrank_projection_closure.sql"));
        assertThat(sql)
                .contains("UPDATE nx_user u")
                .contains("UPDATE nx_team_member projection")
                .contains("user_id=member_user_id")
                .contains("MAX(id) latest_id");
    }
}
