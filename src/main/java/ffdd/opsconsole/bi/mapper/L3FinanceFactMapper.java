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
            SELECT COUNT(DISTINCT CASE WHEN e.event_type = 'withdraw.submitted' THEN e.aggregate_id END) AS submitted,
                   COUNT(DISTINCT CASE WHEN e.event_type = 'withdraw.confirmed' THEN e.aggregate_id END) AS confirmed,
                   COUNT(DISTINCT CASE WHEN e.event_type = 'withdraw.rejected' THEN e.aggregate_id END) AS rejected,
                   COUNT(DISTINCT CASE WHEN e.event_type = 'withdraw.delayed' THEN e.aggregate_id END) AS delayedCount,
                   COUNT(DISTINCT CASE WHEN e.event_type = 'withdraw.frozen' THEN e.aggregate_id END) AS frozen,
                   AVG(CASE
                         WHEN e.event_type = 'withdraw.confirmed'
                              AND submitted.submitted_at IS NOT NULL
                              AND e.event_ts >= submitted.submitted_at
                         THEN TIMESTAMPDIFF(SECOND, submitted.submitted_at, e.event_ts) / 3600.0
                       END) AS avgLatencyHours
              FROM nx_event_outbox e
              JOIN nx_withdrawal_order w
                ON w.withdrawal_no = e.aggregate_id AND w.is_deleted = 0
              LEFT JOIN (
                SELECT aggregate_id, MIN(event_ts) AS submitted_at
                  FROM nx_event_outbox
                 WHERE is_deleted = 0
                   AND aggregate_type = 'WITHDRAWAL'
                   AND event_type = 'withdraw.submitted'
                   AND is_server_authoritative = 1
                 GROUP BY aggregate_id
              ) submitted ON submitted.aggregate_id = e.aggregate_id
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
             WHERE e.is_deleted = 0
               AND e.aggregate_type = 'WITHDRAWAL'
               AND e.is_server_authoritative = 1
               AND e.event_type IN (
                 'withdraw.submitted', 'withdraw.confirmed', 'withdraw.rejected',
                 'withdraw.delayed', 'withdraw.frozen')
               AND e.event_ts >= #{from}
               AND e.event_ts &lt; #{to}
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
