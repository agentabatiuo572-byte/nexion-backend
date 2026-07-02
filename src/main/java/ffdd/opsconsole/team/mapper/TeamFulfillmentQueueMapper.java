package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface TeamFulfillmentQueueMapper extends BaseMapper<Object> {
    @Select("""
            SELECT rank_code AS rankCode,
                   reward_name AS rewardName,
                   status,
                   COUNT(*) AS count
              FROM nx_v_rank_reward_fulfillment
             WHERE is_deleted = 0
             GROUP BY rank_code, reward_name, status
             ORDER BY FIELD(status, 'PENDING', 'PROCESSING', 'FULFILLED', 'FAILED', 'CANCELLED'),
                      MIN(created_at),
                      rank_code,
                      reward_name
             LIMIT 50
            """)
    List<TeamFulfillmentQueueRow> fulfillmentQueues();
}
