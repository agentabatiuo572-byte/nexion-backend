package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * F1 V-Rank 业绩聚合层 Mapper(MyBatis 注解 SQL)。
 *
 * <p>对应 {@link ffdd.opsconsole.team.domain.VRankPerformanceRepository} 的 4 个聚合查询:
 * <ol>
 *   <li>{@link #selfBuyUSD} — 用户自己已付订单的 SUM(subtotal_usdt)</li>
 *   <li>{@link #teamVolumeUSD} — L1-L7 递归 CTE 子树所有成员 selfBuy 之和(限深 7,不含自己)</li>
 *   <li>{@link #directRefsCount} — L1 直接成员中 selfBuy ≥ V1 门槛 AND KYC APPROVED 的 COUNT DISTINCT</li>
 *   <li>{@link #legCountsByLevel} — L1 直接成员按 v_rank 阶数字分组的成员数</li>
 * </ol>
 *
 * <p>默认口径见 {@link ffdd.opsconsole.team.domain.VRankEvaluationSnapshot}。
 *
 * <p>注:继承 {@code BaseMapper<Object>} 仅满足架构层约束(OpsConsoleArchitectureTest 要求所有 mapper
 * 继承 BaseMapper);本 mapper 不使用 BaseMapper 内置 CRUD,所有 SQL 走 @Select 注解。
 */
public interface VRankPerformanceMapper extends BaseMapper<Object> {

    /** 用户自己已付订单 SUM(subtotal_usdt)。 */
    @Select("""
            SELECT COALESCE(SUM(o.subtotal_usdt), 0)
              FROM nx_order o
             WHERE o.user_id = #{userId}
               AND o.payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
               AND o.order_status NOT IN ('REFUNDED', 'CHARGEBACK')
               AND o.is_deleted = 0
            """)
    BigDecimal selfBuyUSD(@Param("userId") Long userId);

    /**
     * L1-L7 子树所有成员 selfBuy 之和(WITH RECURSIVE 限深 7,不含用户自己)。
     *
     * <p>递归 CTE 起点:user_id=userId AND level=1 的直接成员(depth=1);
     * 递归步:加入 level=1 直接父子边扩展深度,WHERE s.depth &lt; 7 终止。
     */
    @Select("""
            WITH RECURSIVE subtree AS (
                SELECT member_user_id, 1 AS depth
                  FROM nx_team_member
                 WHERE user_id = #{userId}
                   AND level = 1
                   AND is_deleted = 0
                UNION ALL
                SELECT c.member_user_id, s.depth + 1
                  FROM subtree s
                  JOIN nx_team_member c
                    ON c.user_id = s.member_user_id
                   AND c.level = 1
                   AND c.is_deleted = 0
                 WHERE s.depth < 7
            )
            SELECT COALESCE(SUM(o.subtotal_usdt), 0)
              FROM subtree s
              LEFT JOIN nx_order o
                ON o.user_id = s.member_user_id
               AND o.payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
               AND o.order_status NOT IN ('REFUNDED', 'CHARGEBACK')
               AND o.is_deleted = 0
            """)
    BigDecimal teamVolumeUSD(@Param("userId") Long userId);

    /**
     * L1 直接成员中 selfBuy ≥ v1SelfBuyThreshold AND KYC APPROVED 的 COUNT DISTINCT。
     *
     * @param v1SelfBuyThreshold V1 阶 self_buy_usd 门槛(来自 nx_v_rank_config)
     */
    @Select("""
            SELECT COUNT(*) FROM (
                SELECT m.member_user_id
                  FROM nx_team_member m
                  INNER JOIN nx_kyc_profile k
                    ON k.user_id = m.member_user_id
                   AND k.is_deleted = 0
                   AND UPPER(k.status) = 'APPROVED'
                  LEFT JOIN nx_order o
                    ON o.user_id = m.member_user_id
                   AND o.payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
                   AND o.order_status NOT IN ('REFUNDED', 'CHARGEBACK')
                   AND o.is_deleted = 0
                 WHERE m.user_id = #{userId}
                   AND m.level = 1
                   AND m.is_deleted = 0
                 GROUP BY m.member_user_id
                HAVING COALESCE(SUM(o.subtotal_usdt), 0) >= #{v1SelfBuyThreshold}
            ) AS qualified_refs
            """)
    int directRefsCount(@Param("userId") Long userId,
                        @Param("v1SelfBuyThreshold") BigDecimal v1SelfBuyThreshold);

    /**
     * L1 直接成员按 v_rank 阶数字分组计数(V0=0..V12=12)。
     *
     * <p>返回每行 {@code (vLevel, memberCount)},v_level 已 CAST 为 INT。
     * 引擎消费时:legCounts[targetV] = SUM(memberCount WHERE vLevel >= targetV)。
     */
    @Select("""
            SELECT CAST(REPLACE(UPPER(m.v_rank), 'V', '') AS SIGNED) AS vLevel,
                   COUNT(DISTINCT m.member_user_id) AS memberCount
              FROM nx_team_member m
             WHERE m.user_id = #{userId}
               AND m.level = 1
               AND m.is_deleted = 0
               AND UPPER(m.v_rank) REGEXP '^V[0-9]{1,2}$'
             GROUP BY CAST(REPLACE(UPPER(m.v_rank), 'V', '') AS SIGNED)
            """)
    List<Map<String, Object>> legCountsByLevel(@Param("userId") Long userId);

    /**
     * 读 V1 阶 self_buy_usd 门槛(供 directRefs 阈值用)。
     * V1 行不存在时返回 0(口径退化:无 V1 配置时 directRefs 永远满足,需上层规避)。
     */
    @Select("""
            SELECT COALESCE(self_buy_usd, 0)
              FROM nx_v_rank_config
             WHERE rank_code = 'V1'
               AND is_deleted = 0
               AND status = 1
             LIMIT 1
            """)
    BigDecimal v1SelfBuyThreshold();
}
