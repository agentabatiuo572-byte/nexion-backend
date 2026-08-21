package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppProofMapper extends BaseMapper<Object> {
    @Select("""
            SELECT created_at AS joinedAt, referral_code AS referralCode
              FROM nx_user
             WHERE id = #{userId} AND status = 'ACTIVE' AND is_deleted = 0
             LIMIT 1
            """)
    UserRow user(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user_device
             WHERE user_id = #{userId} AND is_deleted = 0
               AND status IN ('ONLINE', 'BUSY', 'ACTIVE', 'RUNNING')
            """)
    long onlineDevices(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM nx_wallet_ledger
             WHERE user_id = #{userId} AND is_deleted = 0
               AND UPPER(asset) = 'USDT' AND UPPER(direction) = 'IN'
               AND UPPER(status) IN ('SUCCESS', 'POSTED', 'CREDITED', 'SETTLED', 'AVAILABLE')
            """)
    BigDecimal earningsTotalUsdt(@Param("userId") Long userId);

    @Select("""
            SELECT SUM(amount)
              FROM nx_funds_sandbox_ledger
             WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(direction)='IN' AND UPPER(source_environment)='SANDBOX'
            """)
    BigDecimal sandboxEarningsTotalUsdt(@Param("runId") String runId, @Param("userId") Long userId);

    /** The persisted H5 check-in projection; date values are business dates, not client timestamps. */
    @Select("""
            SELECT current_streak AS currentStreak,
                   longest_streak AS longestStreak,
                   last_check_in_date AS lastCheckInDate
              FROM nx_user_streak
             WHERE user_id = #{userId} AND is_deleted = 0
             LIMIT 1
            """)
    StreakRow streak(@Param("userId") Long userId);

    /**
     * Rank only against the pre-aggregated daily earnings projection. This
     * avoids summing the append-only wallet ledger for every proof request.
     * Sandbox users are excluded; sandbox has no canonical percentile fact.
     */
    @Select("""
            SELECT
              COALESCE(SUM(CASE WHEN totals.earned_usdt > #{earnings} THEN 1 ELSE 0 END), 0) AS higherCount,
              COUNT(*) AS populationCount
              FROM (
                SELECT s.user_id, SUM(s.usdt_amount) AS earned_usdt
                  FROM nx_earning_summary s
                  JOIN nx_user u ON u.id = s.user_id
                                AND u.status = 'ACTIVE'
                                AND u.is_deleted = 0
                                AND COALESCE(u.sandbox, 0) = 0
                 WHERE s.is_deleted = 0 AND s.usdt_amount > 0
                 GROUP BY s.user_id
                UNION ALL
                SELECT #{userId}, #{earnings}
                 WHERE NOT EXISTS (
                     SELECT 1 FROM nx_earning_summary own
                      WHERE own.user_id = #{userId} AND own.is_deleted = 0 AND own.usdt_amount > 0
                 )
              ) totals
            """)
    PercentileRow earningsPopulation(@Param("userId") Long userId, @Param("earnings") BigDecimal earnings);

    record UserRow(LocalDateTime joinedAt, String referralCode) { }
    record StreakRow(Integer currentStreak, Integer longestStreak, LocalDate lastCheckInDate) { }
    record PercentileRow(Long higherCount, Long populationCount) { }
}
