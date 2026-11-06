package ffdd.opsconsole.bi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.mapper.D1HdPayReadSql;
import ffdd.opsconsole.shared.config.BusinessPeriodSql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface L3FinanceFactMapper extends BaseMapper<Object> {
    String DEVICE_SALES_COHORT = """
              FROM nx_order
             WHERE is_deleted = 0
               AND payment_status = 'PAID'
               AND order_status = 'COMPLETED'
               AND COALESCE(paid_at, created_at) >= #{from}
               AND COALESCE(paid_at, created_at) < #{to}
            """;

    @Select("SELECT COALESCE(SUM(amount_usdt), 0) " + DEVICE_SALES_COHORT)
    BigDecimal sumDeviceSalesGmv(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(*) " + DEVICE_SALES_COHORT)
    long countDeviceSalesOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    String FLOW_DATE = "DATE(" + BusinessPeriodSql.Legacy.BUSINESS_TIME_HEAD + "occurred_at"
            + BusinessPeriodSql.Legacy.BUSINESS_TIME_MID + "occurred_at"
            + BusinessPeriodSql.Legacy.BUSINESS_TIME_TAIL + ")";

    @Select("WITH " + D1HdPayReadSql.CTES + """
            , flow_facts AS (
                SELECT l.created_at AS occurred_at,
                       CASE WHEN p.id IS NOT NULL THEN l.amount + p.fee_amount_usdt ELSE l.amount END AS amount,
                       1 AS topup
                  FROM nx_wallet_ledger l
                  LEFT JOIN nx_deposit_order d
                    ON d.ledger_id=l.id AND d.is_deleted=0 AND d.user_id=l.user_id
                   AND d.deposit_no=l.biz_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
                   AND d.asset=l.asset AND d.amount=l.amount
                  LEFT JOIN nx_payment_record p
                    ON p.wallet_ledger_id=l.id AND p.is_deleted=0 AND p.user_id=l.user_id
                   AND p.amount_usdt=l.amount AND l.biz_type='CARD_TOPUP' AND l.biz_no=p.payment_no
                 WHERE l.is_deleted=0 AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS'
                   AND l.created_at >= #{from} AND l.created_at < #{to}
                   AND (d.id IS NOT NULL OR p.id IS NOT NULL)
                UNION ALL
                SELECT ledger_created_at, settled_usdt, 1
                  FROM hdpay_credited
                 WHERE ledger_created_at >= #{from} AND ledger_created_at < #{to}
                UNION ALL
                SELECT completed_at, amount, 0
                  FROM nx_withdrawal_order
                 WHERE is_deleted=0 AND asset='USDT' AND status IN ('CONFIRMED','SUCCESS')
                   AND completed_at >= #{from} AND completed_at < #{to}
            )
            SELECT
            """ + FLOW_DATE + """
                   AS date,
                   COALESCE(SUM(CASE WHEN topup=1 THEN amount END),0) AS userTopupUsdt,
                   COUNT(CASE WHEN topup=1 THEN 1 END) AS userTopupCount,
                   COALESCE(SUM(CASE WHEN topup=0 THEN amount END),0) AS withdrawalUsdt,
                   COUNT(CASE WHEN topup=0 THEN 1 END) AS withdrawalCount
              FROM flow_facts
             GROUP BY 1
            """)
    List<DailyFlowFact> dailyFlows(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    record DailyFlowFact(LocalDate date, BigDecimal userTopupUsdt, long userTopupCount,
                         BigDecimal withdrawalUsdt, long withdrawalCount) {}

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
               <if test="cohort != null">
               AND u.created_at >= #{cohort.from}
               AND u.created_at &lt; #{cohort.to}
               </if>
            </script>
            """)
    Map<String, Object> redemptionSummary(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("cohort") CohortWindow cohort);

    record CohortWindow(LocalDateTime from, LocalDateTime to) {}
}
