package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * F1 V-Rank 业绩聚合层 Mapper(MyBatis 注解 SQL)。
 *
 * <p>对应 {@link ffdd.opsconsole.team.domain.VRankPerformanceRepository} 的 4 个聚合查询:
 * <ol>
 *   <li>{@link #selfComputePoints} — 用户自己已付订单的 SUM(compute_points)</li>
 *   <li>{@link #teamComputePoints} — L1-L7 递归 CTE 子树所有成员自购算力点之和(限深 7,不含自己)</li>
 *   <li>{@link #directRefsCount} — L1-L2 成员中有有效付款订单的去重人数</li>
 *   <li>{@link #legCountsByLevel} — L1-L2 成员按当前 v_rank 分组的去重人数</li>
 * </ol>
 *
 * <p>默认口径见 {@link ffdd.opsconsole.team.domain.VRankEvaluationSnapshot}。
 *
 * <p>注:继承 {@code BaseMapper<Object>} 仅满足架构层约束(OpsConsoleArchitectureTest 要求所有 mapper
 * 继承 BaseMapper);本 mapper 不使用 BaseMapper 内置 CRUD,所有 SQL 走 @Select 注解。
 */
public interface VRankPerformanceMapper extends BaseMapper<Object> {

    /** 用户自己已付订单 SUM(compute_points)。 */
    @Select("""
            SELECT COALESCE(SUM(o.compute_points), 0)
              FROM nx_order o
             WHERE o.user_id = #{userId}
               AND o.payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
               AND o.order_status NOT IN ('REFUNDED', 'CHARGEBACK')
               AND o.is_deleted = 0
            """)
    long selfComputePoints(@Param("userId") Long userId);

    /**
     * L1-L7 子树所有成员自购算力点之和(WITH RECURSIVE 限深 7,不含用户自己)。
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
            SELECT COALESCE(SUM(o.compute_points), 0)
              FROM subtree s
              LEFT JOIN nx_order o
                ON o.user_id = s.member_user_id
               AND o.payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
               AND o.order_status NOT IN ('REFUNDED', 'CHARGEBACK')
               AND o.is_deleted = 0
            """)
    long teamComputePoints(@Param("userId") Long userId);

    // Use the same active L1 edges as teamComputePoints, with only two levels.
    String TWO_LEVEL_MEMBERS = """
            WITH direct_members AS (
                SELECT DISTINCT member_user_id
                  FROM nx_team_member
                 WHERE user_id = #{userId}
                   AND member_user_id <> #{userId}
                   AND level = 1 AND is_deleted = 0
            ),
            downline_members AS (
                SELECT member_user_id FROM direct_members
                UNION DISTINCT
                SELECT child.member_user_id
                  FROM direct_members parent
                  JOIN nx_team_member child ON child.user_id = parent.member_user_id
                 WHERE child.member_user_id <> #{userId}
                   AND child.level = 1 AND child.is_deleted = 0
            )
            """;

    @Select(TWO_LEVEL_MEMBERS + """
            SELECT COUNT(*)
              FROM downline_members downline
             WHERE EXISTS (
                SELECT 1 FROM nx_order o
                 WHERE o.user_id = downline.member_user_id
                   AND o.payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
                   AND o.order_status NOT IN ('REFUNDED', 'CHARGEBACK')
                   AND o.is_deleted = 0
             )
            """)
    int directRefsCount(@Param("userId") Long userId);

    /** The API returns exact ranks. The promotion engine totals these at or above the required rank. */
    @Select(TWO_LEVEL_MEMBERS + """
            SELECT CAST(SUBSTRING(canonical.v_rank, 2) AS SIGNED) AS vLevel,
                   COUNT(*) AS memberCount
              FROM downline_members downline
              JOIN nx_team_member canonical ON canonical.id = (
                  SELECT self.id FROM nx_team_member self
                   WHERE self.user_id = downline.member_user_id
                     AND self.member_user_id = downline.member_user_id
                     AND self.is_deleted = 0
                   ORDER BY self.id
                   LIMIT 1
              )
             WHERE UPPER(canonical.v_rank) REGEXP '^V[0-9]{1,2}$'
             GROUP BY CAST(SUBSTRING(canonical.v_rank, 2) AS SIGNED)
            """)
    List<Map<String, Object>> legCountsByLevel(@Param("userId") Long userId);
}
