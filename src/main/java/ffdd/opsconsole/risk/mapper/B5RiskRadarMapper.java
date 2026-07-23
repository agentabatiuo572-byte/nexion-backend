package ffdd.opsconsole.risk.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Read-only radar aggregates span risk, withdrawal, reserve, and wallet tables.
public interface B5RiskRadarMapper {

    @Select("""
            SELECT
              (SELECT COALESCE(SUM(amount), 0)
                 FROM nx_withdrawal_order
                WHERE is_deleted=0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS withdraw24hUsdt,
              (SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_usd ELSE -amount_usd END), 0)
                 FROM nx_treasury_reserve_ledger
                WHERE is_deleted=0 AND status='CONFIRMED') AS reserveUsdt,
              (SELECT COALESCE(SUM(CASE
                         WHEN direction='OUT' AND (UPPER(biz_type) LIKE '%WITHDRAW%'
                              OR UPPER(biz_type) LIKE '%PAYOUT%') THEN amount ELSE 0 END), 0)
                 FROM nx_wallet_ledger
                WHERE is_deleted=0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS payoutUsdt,
              (SELECT COALESCE(SUM(CASE
                         WHEN direction='OUT' AND UPPER(biz_type) LIKE '%COMMISSION%' THEN amount ELSE 0 END), 0)
                 FROM nx_wallet_ledger
                WHERE is_deleted=0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS commissionUsdt,
              (SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount ELSE 0 END), 0)
                 FROM nx_wallet_ledger
                WHERE is_deleted=0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS grossInflowUsdt
            """)
    Map<String, Object> moneySnapshot();

    @Select("""
            SELECT states.state,
                   COUNT(w.id) AS count,
                   COALESCE(SUM(w.amount), 0) AS amountUsdt,
                   COALESCE(SUM(CASE WHEN w.created_at < DATE_SUB(NOW(), INTERVAL 48 HOUR) THEN 1 ELSE 0 END), 0)
                     AS overSlaCount
              FROM (
                    SELECT 'submitted' AS state
                    UNION ALL SELECT 'review-passed'
                    UNION ALL SELECT 'processing'
              ) states
              LEFT JOIN nx_withdrawal_order w
                ON w.is_deleted=0
               AND states.state = CASE
                    WHEN UPPER(w.status) IN ('SUBMITTED','PENDING') THEN 'submitted'
                    WHEN UPPER(w.status) IN ('REVIEW_PASSED','PENDING_CHAIN') THEN 'review-passed'
                    WHEN UPPER(w.status)='PROCESSING' THEN 'processing'
                    ELSE NULL
               END
             GROUP BY states.state
             ORDER BY FIELD(states.state,'submitted','review-passed','processing')
            """)
    List<Map<String, Object>> withdrawalBacklog();

    @Select("""
            SELECT categories.category, categories.label, COUNT(DISTINCT categories.userId) AS count
              FROM (
                    SELECT CASE
                             WHEN UPPER(signal_type) REGEXP 'MULTI|CLUSTER' THEN 'multi-account'
                             WHEN UPPER(signal_type) LIKE '%ARBITRAGE%' THEN 'arbitrage'
                             WHEN UPPER(signal_type) REGEXP 'TRIAL|CYCLE' THEN 'trial-cycle'
                             WHEN UPPER(signal_type) REGEXP 'WITHDRAW|HOLD|FROZEN' THEN 'withdraw-held'
                           END AS category,
                           CASE
                             WHEN UPPER(signal_type) REGEXP 'MULTI|CLUSTER' THEN '反多账户命中'
                             WHEN UPPER(signal_type) LIKE '%ARBITRAGE%' THEN '套利可疑'
                             WHEN UPPER(signal_type) REGEXP 'TRIAL|CYCLE' THEN 'Trial 循环养号'
                             WHEN UPPER(signal_type) REGEXP 'WITHDRAW|HOLD|FROZEN' THEN '提现冻结'
                           END AS label,
                           user_id AS userId
                      FROM nx_risk_signal
                     WHERE is_deleted=0
                       AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                       AND UPPER(signal_type) REGEXP 'MULTI|CLUSTER|ARBITRAGE|TRIAL|CYCLE|WITHDRAW|HOLD|FROZEN'
                    UNION ALL
                    SELECT 'withdraw-held', '提现冻结', user_id
                      FROM nx_withdrawal_order
                     WHERE is_deleted=0 AND UPPER(status)='FROZEN'
              ) categories
             GROUP BY categories.category, categories.label
             ORDER BY FIELD(categories.category,'multi-account','arbitrage','trial-cycle','withdraw-held')
            """)
    List<Map<String, Object>> abnormalAccountCategories();
}
