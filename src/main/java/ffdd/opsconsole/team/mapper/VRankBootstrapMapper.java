package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.team.dto.VRankBootstrapRequest;
import ffdd.opsconsole.team.infrastructure.VRankConfigEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface VRankBootstrapMapper extends BaseMapper<VRankConfigEntity> {
    @Select("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='F1_VRANK_BOOTSTRAP' FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<String> lockBootstrapMutex();

    // Bootstrap requires complete emptiness, including disabled and soft-deleted rows.
    @Select("SELECT id FROM nx_v_rank_config FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockAllRankIds();

    @Select("SELECT id FROM nx_v_rank_reward_rule FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockAllRewardIds();

    @Insert("""
            INSERT INTO nx_v_rank_config(rank_code,title_cn,title_en,self_compute_points,direct_refs,
              team_compute_points,required_downline_rank,required_downline_count,unilevel_depth,
              peer_bonus_rate,leadership_votes,sort_order,status,is_deleted)
            VALUES(#{rank.rankCode},#{rank.titleCn},#{rank.titleEn},#{rank.selfComputePoints},#{rank.directRefs},
              #{rank.teamComputePoints},#{rank.requiredDownlineRank},#{rank.requiredDownlineCount},
              #{rank.unilevelDepth},#{rank.peerBonusRate},#{rank.leadershipVotes},#{sortOrder},1,0)
            """)
    int insertRank(@Param("rank") VRankBootstrapRequest.Rank rank, @Param("sortOrder") int sortOrder);
}
