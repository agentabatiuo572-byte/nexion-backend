package ffdd.opsconsole.bi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface L3FinanceFactMapper extends BaseMapper<Object> {
    @Select("""
            SELECT COALESCE(SUM(amount_usdt), 0)
              FROM nx_order
             WHERE is_deleted = 0
               AND payment_status = 'PAID'
               AND order_status = 'COMPLETED'
               AND COALESCE(paid_at, created_at) >= #{from}
               AND COALESCE(paid_at, created_at) < #{to}
            """)
    BigDecimal sumDeviceSalesGmv(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM nx_wallet_ledger
             WHERE is_deleted = 0
               AND biz_type = 'TEAM_COMMISSION'
               AND status IN ('PENDING', 'POSTED', 'SUCCESS')
               AND created_at >= #{from}
               AND created_at < #{to}
            """)
    BigDecimal sumTeamCommission(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(SUM(
                     CASE
                       WHEN to_asset = 'USDT' THEN to_amount
                       WHEN from_asset = 'USDT' THEN from_amount
                       ELSE 0
                     END), 0)
              FROM nx_exchange_order
             WHERE is_deleted = 0
               AND status = 'COMPLETED'
               AND created_at >= #{from}
               AND created_at < #{to}
            """)
    BigDecimal sumTokenEconomyVolume(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM nx_wallet_ledger
             WHERE is_deleted = 0
               AND biz_type IN ('COMPUTE_MATCHING_FEE', 'COMPUTE_SERVICE_FEE', 'TASK_SERVICE_FEE')
               AND direction = 'IN'
               AND status IN ('POSTED', 'SUCCESS')
               AND created_at >= #{from}
               AND created_at < #{to}
            """)
    BigDecimal sumComputeMatchingFees(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            <script>
            SELECT COUNT(*) AS submitted,
                   COALESCE(SUM(CASE WHEN UPPER(w.status) IN ('COMPLETED', 'CONFIRMED') THEN 1 ELSE 0 END), 0) AS confirmed,
                   COALESCE(SUM(CASE WHEN UPPER(w.status) IN ('REJECTED', 'REFUNDED') THEN 1 ELSE 0 END), 0) AS rejected,
                   COALESCE(SUM(CASE WHEN UPPER(w.status) = 'DELAYED' THEN 1 ELSE 0 END), 0) AS delayedCount,
                   COALESCE(SUM(CASE WHEN UPPER(w.status) = 'FROZEN' THEN 1 ELSE 0 END), 0) AS frozen,
                   AVG(CASE
                         WHEN UPPER(w.status) IN ('COMPLETED', 'CONFIRMED') AND w.completed_at IS NOT NULL
                         THEN TIMESTAMPDIFF(SECOND, w.created_at, w.completed_at) / 3600.0
                       END) AS avgLatencyHours
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
             WHERE w.is_deleted = 0
               AND w.created_at >= #{from}
               AND w.created_at &lt; #{to}
               <if test="cohort != null and cohort != ''">
               AND DATE_FORMAT(u.created_at, '%Y-%m') = #{cohort}
               </if>
            </script>
            """)
    Map<String, Object> redemptionSummary(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("cohort") String cohort);
}
