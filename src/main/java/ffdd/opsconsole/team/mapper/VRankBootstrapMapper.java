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
              peer_bonus_rate,leadership_votes,sort_order,status,is_deleted,purchase_l1_rate,purchase_l2_rate,monthly_salary_usdt)
            VALUES(#{rank.rankCode},#{rank.titleCn},#{rank.titleEn},#{rank.selfComputePoints},#{rank.directRefs},
              #{rank.teamComputePoints},#{rank.requiredDownlineRank},#{rank.requiredDownlineCount},
              #{rank.unilevelDepth},#{rank.peerBonusRate},#{rank.leadershipVotes},#{sortOrder},1,0,
              ELT(#{sortOrder}+1,.040,.040,.042,.044,.047,.052,.058,.066,.080),
              ELT(#{sortOrder}+1,.020,.020,.021,.022,.025,.029,.035,.042,.055),
              ELT(#{sortOrder}+1,0,0,20,50,150,500,1500,4000,15000))
            """)
    int insertRank(@Param("rank") VRankBootstrapRequest.Rank rank, @Param("sortOrder") int sortOrder);

    @Insert("""
            INSERT IGNORE INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
            VALUES('team.purchase-rank.cutover-at','PENDING','STRING','team','ADMIN','B83 好友购买奖励按等级生效时刻',1,0)
            """)
    int enablePurchaseRankCoverageCheck();

    @Insert("""
            INSERT INTO nx_v_rank_salary_rate(rank_code,effective_at,amount_usdt)
            SELECT rank_code,NOW(6),monthly_salary_usdt FROM nx_v_rank_config WHERE is_deleted=0
            """)
    int seedMonthlySalaryRates();
}
