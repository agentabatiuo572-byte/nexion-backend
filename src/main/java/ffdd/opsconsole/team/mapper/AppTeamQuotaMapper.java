package ffdd.opsconsole.team.mapper;

import ffdd.opsconsole.shared.config.HardwareQuotaPeriod;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppTeamQuotaMapper extends BaseMapper<Object> {
    @Select("""
            SELECT u.sandbox, COALESCE(tm.v_rank, u.v_rank) AS vRank
              FROM nx_user u
              LEFT JOIN nx_team_member tm ON tm.user_id = u.id
               AND tm.member_user_id = u.id AND tm.is_deleted = 0
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0
             LIMIT 1
            """)
    UserScope userScope(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
             WHERE u.id=#{userId}
               AND REPLACE(TRIM(COALESCE(u.country_code,'')),'+','')=REPLACE(#{countryCode},'+','')
               AND u.phone=#{phone} AND u.sandbox=1
               AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    int developmentUserScope(@Param("userId") Long userId,
                             @Param("countryCode") String countryCode,
                             @Param("phone") String phone);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user child
              JOIN nx_user owner ON owner.id = #{userId}
               AND owner.status = 'ACTIVE' AND owner.is_deleted = 0
               AND child.sponsor_user_id = owner.id
               AND child.sandbox = owner.sandbox
             WHERE child.status = 'ACTIVE' AND child.is_deleted = 0
            """)
    long activeDirect(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user child
              JOIN nx_user owner ON owner.id = #{userId}
               AND owner.status = 'ACTIVE' AND owner.is_deleted = 0
               AND child.sponsor_user_id = owner.id AND child.sandbox = owner.sandbox
             WHERE child.is_deleted = 0
            """)
    long directInvites(@Param("userId") Long userId);

    @Select("""
            SELECT t.quota_code AS quotaCode, t.product_no AS productNo,
                   COALESCE(t.display_name, t.quota_code) AS name,
                   t.direct_refs AS directRefs, t.month_compute_points AS monthComputePoints,
                   t.monthly_quota AS monthlyQuota, t.unlock_mode AS unlockMode,
                   COALESCE(SUM(CASE WHEN u.is_deleted = 0 AND UPPER(u.status) = 'ACTIVE'
                         AND u.occurred_at >= """ + HardwareQuotaPeriod.SQL_MONTH_START_UTC + """

                         AND u.occurred_at < """ + HardwareQuotaPeriod.SQL_NEXT_MONTH_START_UTC + """

                         THEN u.quantity ELSE 0 END), 0) AS soldThisMonth
              FROM nx_team_hardware_quota_tier t
              LEFT JOIN nx_team_hardware_quota_usage u ON u.quota_tier_id = t.id
             WHERE t.is_deleted = 0 AND t.status = 1
             GROUP BY t.id, t.quota_code, t.product_no, t.display_name,
                      t.direct_refs, t.month_compute_points, t.monthly_quota,
                      t.unlock_mode, t.sort_order
             ORDER BY t.sort_order ASC, t.id ASC
            """)
    List<QuotaRow> quotaRows();

    /** Team compute points for the current quota month, same window and subtree as the F4b purchase guard. */
    @Select("""
            WITH RECURSIVE subtree AS (
                SELECT tm.member_user_id, owner.sandbox, 1 AS depth
                  FROM nx_team_member tm
                  JOIN nx_user owner ON owner.id=#{userId}
                   AND owner.status='ACTIVE' AND owner.is_deleted=0
                  JOIN nx_user direct ON direct.id=tm.member_user_id
                   AND direct.sandbox=owner.sandbox AND direct.status='ACTIVE' AND direct.is_deleted=0
                 WHERE tm.user_id=owner.id AND tm.level=1 AND tm.is_deleted=0
                UNION ALL
                SELECT child_member.member_user_id, s.sandbox, s.depth + 1
                  FROM subtree s
                  JOIN nx_team_member child_member ON child_member.user_id=s.member_user_id
                   AND child_member.level=1 AND child_member.is_deleted=0
                  JOIN nx_user child ON child.id=child_member.member_user_id
                   AND child.sandbox=s.sandbox AND child.status='ACTIVE' AND child.is_deleted=0
                 WHERE s.depth < 7
            )
            SELECT COALESCE(SUM(o.compute_points),0)
              FROM subtree s
              LEFT JOIN nx_order o ON o.user_id=s.member_user_id
               AND o.payment_status IN ('PAID','CONFIRMED','SUCCESS')
               AND o.order_status NOT IN ('REFUNDED','CHARGEBACK')
               AND COALESCE(o.paid_at,o.created_at)>=""" + HardwareQuotaPeriod.SQL_MONTH_START_ORDER_STORAGE + """

               AND COALESCE(o.paid_at,o.created_at)<""" + HardwareQuotaPeriod.SQL_NEXT_MONTH_START_ORDER_STORAGE + """

               AND o.is_deleted=0
            """)
    BigDecimal monthlyTeamComputePoints(@Param("userId") Long userId);

    record UserScope(Integer sandbox, String vRank) { }
    record QuotaRow(String quotaCode, String productNo, String name, Integer directRefs,
                    BigDecimal monthComputePoints, Integer monthlyQuota, String unlockMode,
                    Long soldThisMonth) { }
}
