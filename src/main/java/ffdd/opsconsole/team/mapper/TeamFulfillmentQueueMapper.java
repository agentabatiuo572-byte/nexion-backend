package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
import ffdd.opsconsole.team.domain.VRankSkuFulfillmentRow;
import java.util.List;
import java.util.Map;
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
            SELECT f.id, f.user_id AS userId, f.rank_code AS rankCode, f.reward_name AS skuId, f.status
              FROM nx_v_rank_reward_fulfillment f
             WHERE f.is_deleted = 0
               AND (f.status = 'PENDING' OR (f.status = 'FAILED' AND f.updated_at <= DATE_SUB(NOW(), INTERVAL 5 MINUTE)))
               AND EXISTS (SELECT 1 FROM nx_v_rank_reward_payout p
                            WHERE p.user_id = f.user_id AND p.rank_code = f.rank_code
                              AND p.sku_id = f.reward_name AND p.reward_type = 'sku'
                              AND p.is_deleted = 0 AND p.status = 'PENDING_GRANT')
             ORDER BY f.created_at, f.id
             LIMIT #{limit}
            """)
    List<VRankSkuFulfillmentRow> pendingSkuFulfillments(@Param("limit") int limit);

    // Worker and F1/A2 dispositions acquire the payout before its exact fulfillment.
    @Select("""
            SELECT status FROM nx_v_rank_reward_payout
             WHERE user_id = #{userId} AND rank_code = #{rankCode} AND sku_id = #{skuId}
               AND reward_type = 'sku' AND is_deleted = 0 FOR UPDATE
            """)
    String lockSkuPayoutStatus(@Param("userId") Long userId, @Param("rankCode") String rankCode,
                               @Param("skuId") String skuId);

    @Select("""
            SELECT id, user_id AS userId, rank_code AS rankCode, reward_name AS skuId, status
              FROM nx_v_rank_reward_fulfillment
             WHERE user_id = #{userId} AND rank_code = #{rankCode} AND reward_name = #{skuId}
               AND is_deleted = 0 ORDER BY id FOR UPDATE
            """)
    List<VRankSkuFulfillmentRow> lockSkuFulfillments(@Param("userId") Long userId,
                                                   @Param("rankCode") String rankCode,
                                                   @Param("skuId") String skuId);

    @Select("""
            SELECT user_id AS userId, rank_code AS rankCode, sku_id AS skuId, status,
                   source, is_deleted AS deleted
              FROM nx_user_sku_entitlement WHERE fulfillment_id = #{id} FOR UPDATE
            """)
    Map<String, Object> lockSkuEntitlement(@Param("id") Long id);

    @Update("""
            UPDATE nx_v_rank_reward_fulfillment
               SET status = 'CANCELLED', reason = LEFT(#{reason},255), updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0 AND status = #{previousStatus}
            """)
    int cancelSkuFulfillment(@Param("id") Long id, @Param("previousStatus") String previousStatus,
                              @Param("reason") String reason);

    @Update("""
            UPDATE nx_v_rank_reward_fulfillment
               SET status = 'PROCESSING', reason = NULL, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0 AND status = 'CANCELLED'
            """)
    int resumeSkuFulfillment(@Param("id") Long id);

    @Update("""
            UPDATE nx_product
               SET stock = CASE WHEN inventory_mode='FINITE' THEN stock + 1 ELSE stock END,
                   sold_count = sold_count - 1,
                   updated_at = GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE product_no = #{skuId} AND is_deleted = 0
               AND (inventory_mode='UNLIMITED' OR stock <= 2147483646) AND sold_count >= 1
            """)
    int returnSkuStock(@Param("skuId") String skuId);

    @Update("""
            UPDATE nx_user_sku_entitlement SET status = #{status}, updated_at = NOW()
             WHERE fulfillment_id = #{id} AND user_id = #{userId} AND rank_code = #{rankCode}
               AND sku_id = #{skuId} AND status = #{previousStatus}
               AND source = 'VRANK_REWARD' AND is_deleted = 0
            """)
    int changeSkuEntitlementStatus(@Param("id") Long id, @Param("userId") Long userId,
                                    @Param("rankCode") String rankCode, @Param("skuId") String skuId,
                                    @Param("previousStatus") String previousStatus, @Param("status") String status);

    @Update("""
            UPDATE nx_v_rank_reward_fulfillment
               SET status = 'PROCESSING', reason = NULL, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0 AND status IN ('PENDING', 'FAILED')
            """)
    int claimSkuFulfillment(@Param("id") Long id);

    @Update("""
            UPDATE nx_product
               SET stock = CASE WHEN inventory_mode='FINITE' THEN stock - 1 ELSE stock END,
                   sold_count = sold_count + 1,
                   updated_at = GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE product_no = #{skuId}
               AND is_deleted = 0
               AND store_visible = 1
               AND UPPER(status) IN ('ACTIVE', 'ON_SALE')
               AND (inventory_mode='FINITE' OR UPPER(product_type)='SHARE')
               AND (inventory_mode='UNLIMITED' OR stock > 0)
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
