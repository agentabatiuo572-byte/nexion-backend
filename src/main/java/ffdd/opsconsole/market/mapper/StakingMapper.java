package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.market.domain.StakingPositionView;
import ffdd.opsconsole.market.domain.StakingProductView;
import ffdd.opsconsole.market.domain.RepurchaseAmountBucketView;
import ffdd.opsconsole.market.domain.RepurchaseStatsView;
import ffdd.opsconsole.market.domain.RepurchaseStatusView;
import ffdd.opsconsole.market.infrastructure.StakingProductEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface StakingMapper extends BaseMapper<StakingProductEntity> {
    @Select("""
            <script>
            SELECT COUNT(1)
              FROM nx_staking_product
             WHERE is_deleted = 0
               AND UPPER(product_code) IN
               <foreach collection='productCodes' item='productCode' open='(' separator=',' close=')'>UPPER(#{productCode})</foreach>
            </script>
            """)
    long countProductsByCodes(@Param("productCodes") List<String> productCodes);

    @Select("""
            SELECT COUNT(1)
              FROM nx_staking_position
             WHERE is_deleted = 0
            """)
    long countPositions();

    @Select("""
            <script>
            SELECT COUNT(1)
              FROM nx_staking_position
             WHERE is_deleted = 0
               AND UPPER(product_code) IN
               <foreach collection='productCodes' item='productCode' open='(' separator=',' close=')'>UPPER(#{productCode})</foreach>
            </script>
            """)
    long countPositionsByProductCodes(@Param("productCodes") List<String> productCodes);

    @Select("""
            <script>
            SELECT product.id,
                   product.product_code AS productCode,
                   product.name AS productName,
                   product.asset,
                   product.term_days AS termDays,
                   product.apy_bps AS apyBps,
                   product.early_penalty_bps AS earlyPenaltyBps,
                   product.min_amount AS minAmount,
                   product.sort_order AS sortOrder,
                   UPPER(product.status) AS status,
                   COALESCE(SUM(CASE
                     WHEN UPPER(sp.status) IN ('PENDING_LOCK', 'ACTIVE', 'MATURE_UNCLAIMED')
                       THEN sp.amount_usdt
                     ELSE 0
                   END), 0) AS lockedUsd,
                   COALESCE(SUM(CASE
                     WHEN UPPER(sp.status) IN ('PENDING_LOCK', 'ACTIVE', 'MATURE_UNCLAIMED')
                       THEN 1
                     ELSE 0
                   END), 0) AS positionCount
              FROM nx_staking_product product
              LEFT JOIN nx_staking_position sp
                ON sp.product_id = product.id
               AND sp.is_deleted = 0
             WHERE product.is_deleted = 0
               AND UPPER(product.product_code) IN
               <foreach collection='productCodes' item='productCode' open='(' separator=',' close=')'>UPPER(#{productCode})</foreach>
             GROUP BY product.id,
                      product.product_code,
                      product.name,
                      product.asset,
                      product.term_days,
                      product.apy_bps,
                      product.early_penalty_bps,
                      product.min_amount,
                      product.sort_order,
                      product.status
            ORDER BY product.sort_order ASC, product.term_days ASC, product.id ASC
            </script>
            """)
    List<StakingProductView> listProductsWithMetrics(@Param("productCodes") List<String> productCodes);

    @Select("""
            SELECT COALESCE(SUM(estimated_interest_usdt), 0)
              FROM nx_staking_position
             WHERE is_deleted = 0
               AND UPPER(status) IN ('ACTIVE', 'MATURE_UNCLAIMED')
            """)
    BigDecimal sumEstimatedInterestUsdt();

    @Select("""
            SELECT COUNT(1)
              FROM nx_staking_position
             WHERE is_deleted = 0
               AND UPPER(status) = UPPER(#{status})
            """)
    long countPositionsByStatus(@Param("status") String status);

    @Select("""
            SELECT COUNT(1)
              FROM nx_staking_position
             WHERE is_deleted = 0
               AND UPPER(status) = 'EARLY_WITHDRAWN'
               AND early_withdrawn_at >= #{since}
            """)
    long countEarlyWithdrawnSince(@Param("since") LocalDateTime since);

    @Select("""
            SELECT sp.id,
                   sp.user_id AS userId,
                   CONCAT('U', LPAD(sp.user_id, 8, '0')) AS userNo,
                   COALESCE(usr.nickname, CONCAT('user-', sp.user_id)) AS nickname,
                   sp.position_no AS positionNo,
                   sp.product_code AS productCode,
                   sp.product_name AS productName,
                   sp.amount_usdt AS amountUsdt,
                   sp.apy_bps AS apyBps,
                   sp.early_penalty_bps AS earlyPenaltyBps,
                   sp.term_days AS termDays,
                   sp.locked_at AS lockedAt,
                   sp.unlock_at AS unlockAt,
                   sp.estimated_interest_usdt AS estimatedInterestUsdt,
                   UPPER(sp.status) AS status,
                   CASE UPPER(sp.status)
                     WHEN 'PENDING_LOCK' THEN '待确认'
                     WHEN 'ACTIVE' THEN '计息中'
                     WHEN 'MATURE_UNCLAIMED' THEN '到期未领'
                     WHEN 'CLAIMED' THEN '已领取'
                     WHEN 'EARLY_WITHDRAWN' THEN '提前赎回'
                     WHEN 'SLASHED' THEN '已扣罚'
                     WHEN 'REFUNDED' THEN '已退款'
                     ELSE UPPER(sp.status)
                   END AS statusLabel,
                   CASE UPPER(sp.status)
                     WHEN 'PENDING_LOCK' THEN 'warn'
                     WHEN 'ACTIVE' THEN 'ok'
                     WHEN 'MATURE_UNCLAIMED' THEN 'warn'
                     WHEN 'CLAIMED' THEN 'dim'
                     WHEN 'EARLY_WITHDRAWN' THEN 'bad'
                     WHEN 'SLASHED' THEN 'bad'
                     WHEN 'REFUNDED' THEN 'dim'
                     ELSE 'dim'
                   END AS statusTone,
                   sp.claimed_at AS claimedAt,
                   sp.early_withdrawn_at AS earlyWithdrawnAt
              FROM nx_staking_position sp
              LEFT JOIN nx_user usr
                ON usr.id = sp.user_id
               AND usr.is_deleted = 0
             WHERE sp.is_deleted = 0
               AND UPPER(sp.status) = UPPER(#{status})
             ORDER BY sp.updated_at DESC, sp.id DESC
             LIMIT #{limit}
            """)
    List<StakingPositionView> listPositionsByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COALESCE(SUM(CASE WHEN sp.locked_at >= #{since} THEN 1 ELSE 0 END), 0) AS ordersMonth,
                   COALESCE(SUM(CASE
                     WHEN UPPER(sp.status) IN ('PENDING_LOCK', 'ACTIVE', 'MATURE_UNCLAIMED')
                       THEN sp.amount_usdt
                     ELSE 0
                   END), 0) AS principalUsd,
                   COALESCE(SUM(CASE
                     WHEN UPPER(sp.status) IN ('PENDING_LOCK', 'ACTIVE', 'MATURE_UNCLAIMED')
                       THEN sp.estimated_interest_usdt
                     ELSE 0
                   END), 0) AS estimatedInterestUsd
              FROM nx_staking_position sp
             WHERE sp.is_deleted = 0
               AND UPPER(sp.product_code) IN
               <foreach collection='productCodes' item='productCode' open='(' separator=',' close=')'>UPPER(#{productCode})</foreach>
            </script>
            """)
    RepurchaseStatsView repurchaseStatsSince(
            @Param("since") LocalDateTime since,
            @Param("productCodes") List<String> productCodes);

    @Select("""
            <script>
            SELECT status, orderCount, principalUsd
              FROM (
                    SELECT UPPER(sp.status) AS status,
                           COUNT(1) AS orderCount,
                           COALESCE(SUM(sp.amount_usdt), 0) AS principalUsd
                      FROM nx_staking_position sp
                     WHERE sp.is_deleted = 0
                       AND UPPER(sp.product_code) IN
                       <foreach collection='productCodes' item='productCode' open='(' separator=',' close=')'>UPPER(#{productCode})</foreach>
                     GROUP BY UPPER(sp.status)
                   ) grouped
             ORDER BY FIELD(status, 'PENDING_LOCK', 'ACTIVE', 'MATURE_UNCLAIMED', 'CLAIMED', 'EARLY_WITHDRAWN', 'SLASHED', 'REFUNDED'),
                      status
            </script>
            """)
    List<RepurchaseStatusView> repurchaseStatusBreakdown(@Param("productCodes") List<String> productCodes);

    @Select("""
            <script>
            SELECT sp.amount_usdt AS amountUsdt,
                   COUNT(1) AS orderCount,
                   COALESCE(SUM(sp.amount_usdt), 0) AS principalUsd
              FROM nx_staking_position sp
             WHERE sp.is_deleted = 0
               AND UPPER(sp.status) IN ('PENDING_LOCK', 'ACTIVE', 'MATURE_UNCLAIMED')
               AND UPPER(sp.product_code) IN
               <foreach collection='productCodes' item='productCode' open='(' separator=',' close=')'>UPPER(#{productCode})</foreach>
             GROUP BY sp.amount_usdt
             ORDER BY sp.amount_usdt ASC
            </script>
            """)
    List<RepurchaseAmountBucketView> repurchaseAmountBuckets(@Param("productCodes") List<String> productCodes);
}
