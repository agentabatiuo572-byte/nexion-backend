package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
import ffdd.opsconsole.team.domain.VRankSkuFulfillmentRow;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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

    @Select("""
            SELECT id, user_id AS userId, rank_code AS rankCode, reward_name AS skuId, status
              FROM nx_v_rank_reward_fulfillment
             WHERE is_deleted = 0
               AND (status = 'PENDING' OR (status = 'FAILED' AND updated_at <= DATE_SUB(NOW(), INTERVAL 5 MINUTE)))
             ORDER BY created_at, id
             LIMIT #{limit}
            """)
    List<VRankSkuFulfillmentRow> pendingSkuFulfillments(@Param("limit") int limit);

    @Update("""
            UPDATE nx_v_rank_reward_fulfillment
               SET status = 'PROCESSING', reason = NULL, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0 AND status IN ('PENDING', 'FAILED')
            """)
    int claimSkuFulfillment(@Param("id") Long id);

    @Update("""
            UPDATE nx_admin_device_sku
               SET stock_text = CAST(CAST(stock_text AS UNSIGNED) - 1 AS CHAR),
                   sold = COALESCE(sold, 0) + 1,
                   updated_at = NOW()
             WHERE sku_id = #{skuId}
               AND is_deleted = 0
               AND LOWER(status) IN ('on', 'active', 'on_sale')
               AND stock_text REGEXP '^[0-9]+$'
               AND CAST(stock_text AS UNSIGNED) > 0
            """)
    int reserveSkuStock(@Param("skuId") String skuId);

    @Insert("""
            INSERT INTO nx_user_sku_entitlement
              (fulfillment_id, user_id, sku_id, rank_code, status, source)
            VALUES
              (#{fulfillmentId}, #{userId}, #{skuId}, #{rankCode}, 'GRANTED', 'VRANK_REWARD')
            ON DUPLICATE KEY UPDATE updated_at = NOW()
            """)
    int insertSkuEntitlement(@Param("fulfillmentId") Long fulfillmentId,
                             @Param("userId") Long userId,
                             @Param("skuId") String skuId,
                             @Param("rankCode") String rankCode);

    @Update("""
            UPDATE nx_v_rank_reward_payout
               SET status = 'GRANTED', updated_at = NOW()
             WHERE user_id = #{userId}
               AND rank_code = #{rankCode}
               AND sku_id = #{skuId}
               AND reward_type = 'sku'
               AND status = 'PENDING_GRANT'
               AND is_deleted = 0
            """)
    int grantSkuPayout(@Param("userId") Long userId,
                       @Param("rankCode") String rankCode,
                       @Param("skuId") String skuId);

    @Update("""
            UPDATE nx_v_rank_reward_fulfillment
               SET status = 'FULFILLED', fulfilled_at = NOW(), reason = NULL, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0 AND status = 'PROCESSING'
            """)
    int completeSkuFulfillment(@Param("id") Long id);

    @Update("""
            UPDATE nx_v_rank_reward_fulfillment
               SET status = 'FAILED', reason = LEFT(#{reason}, 255), updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0 AND status IN ('PENDING', 'PROCESSING', 'FAILED')
            """)
    int failSkuFulfillment(@Param("id") Long id, @Param("reason") String reason);

    @Select("""
            SELECT COUNT(1) FROM nx_user_sku_entitlement
             WHERE fulfillment_id = #{fulfillmentId} AND user_id = #{userId}
               AND sku_id = #{skuId} AND status = 'GRANTED' AND is_deleted = 0
            """)
    int countGrantedSkuEntitlement(@Param("fulfillmentId") Long fulfillmentId,
                                   @Param("userId") Long userId,
                                   @Param("skuId") String skuId);
}
