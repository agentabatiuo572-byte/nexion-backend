package ffdd.opsconsole.team.mapper;

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
            SELECT t.quota_code AS quotaCode, t.product_no AS productNo,
                   COALESCE(t.display_name, t.quota_code) AS name,
                   t.direct_refs AS directRefs, t.month_volume_usd AS monthVolumeUsd,
                   t.monthly_quota AS monthlyQuota, t.unlock_mode AS unlockMode,
                   COALESCE(SUM(CASE WHEN u.is_deleted = 0 AND UPPER(u.status) = 'ACTIVE'
                         AND u.occurred_at >= DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01')
                         THEN u.quantity ELSE 0 END), 0) AS soldThisMonth
              FROM nx_team_hardware_quota_tier t
              LEFT JOIN nx_team_hardware_quota_usage u ON u.quota_tier_id = t.id
             WHERE t.is_deleted = 0 AND t.status = 1
             GROUP BY t.id, t.quota_code, t.product_no, t.display_name,
                      t.direct_refs, t.month_volume_usd, t.monthly_quota,
                      t.unlock_mode, t.sort_order
             ORDER BY t.sort_order ASC, t.id ASC
            """)
    List<QuotaRow> quotaRows();

    record UserScope(Integer sandbox, String vRank) { }
    record QuotaRow(String quotaCode, String productNo, String name, Integer directRefs,
                    BigDecimal monthVolumeUsd, Integer monthlyQuota, String unlockMode,
                    Long soldThisMonth) { }
}
