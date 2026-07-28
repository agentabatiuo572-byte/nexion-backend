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
                WHERE is_deleted=0
                  AND asset='USDT'
                  AND UPPER(status) NOT IN ('FAILED','REJECTED','REVIEW_REJECTED','CANCELLED','CANCELED')
                  AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS withdraw24hUsdt,
              (SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_usd ELSE -amount_usd END), 0)
                 FROM nx_treasury_reserve_ledger
                WHERE is_deleted=0 AND status='CONFIRMED') AS reserveUsdt,
              (SELECT COALESCE(SUM(CASE
                         WHEN direction='OUT' AND (UPPER(biz_type) LIKE '%WITHDRAW%'
                              OR UPPER(biz_type) LIKE '%PAYOUT%') THEN amount ELSE 0 END), 0)
                 FROM nx_wallet_ledger
                WHERE is_deleted=0 AND asset='USDT' AND status='SUCCESS'
                  AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS payoutUsdt,
              (SELECT COALESCE(SUM(CASE
                         WHEN direction='OUT' AND UPPER(biz_type) LIKE '%COMMISSION%' THEN amount ELSE 0 END), 0)
                 FROM nx_wallet_ledger
                WHERE is_deleted=0 AND asset='USDT' AND status='SUCCESS'
                  AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS commissionUsdt,
              (SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount ELSE 0 END), 0)
                 FROM nx_wallet_ledger
                WHERE is_deleted=0 AND asset='USDT' AND status='SUCCESS'
                  AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)) AS grossInflowUsdt
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
                    WHEN UPPER(w.status) IN ('SUBMITTED','PENDING','REVIEWING','REVIEW_PENDING','DELAYED','EXTENDED_HOLD','FROZEN') THEN 'submitted'
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
                             WHEN LOWER(signal_type) IN ('risk.multi_account_flagged','risk.multi_account_incident_created') THEN 'multi-account'
                             WHEN LOWER(signal_type) IN ('risk.arbitrage_suspected','risk.leaderboard_velocity_flagged') THEN 'arbitrage'
                             WHEN LOWER(signal_type)='risk.trial_cycle_detected' THEN 'trial-cycle'
                             WHEN LOWER(signal_type)='risk.withdraw_held' THEN 'withdraw-held'
                           END AS category,
                           CASE
                             WHEN LOWER(signal_type) IN ('risk.multi_account_flagged','risk.multi_account_incident_created') THEN '反多账户命中'
                             WHEN LOWER(signal_type) IN ('risk.arbitrage_suspected','risk.leaderboard_velocity_flagged') THEN '套利可疑'
                             WHEN LOWER(signal_type)='risk.trial_cycle_detected' THEN 'Trial 循环养号'
                             WHEN LOWER(signal_type)='risk.withdraw_held' THEN '提现冻结'
                           END AS label,
                           user_id AS userId
                      FROM nx_risk_signal
                     WHERE is_deleted=0
                       AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                       AND LOWER(signal_type) IN (
                         'risk.multi_account_flagged','risk.multi_account_incident_created',
                         'risk.arbitrage_suspected','risk.leaderboard_velocity_flagged',
                         'risk.trial_cycle_detected','risk.withdraw_held')
                    UNION ALL
                    SELECT 'withdraw-held', '提现冻结', user_id
                      FROM nx_withdrawal_order
                     WHERE is_deleted=0 AND UPPER(status)='FROZEN'
              ) categories
             GROUP BY categories.category, categories.label
             ORDER BY FIELD(categories.category,'multi-account','arbitrage','trial-cycle','withdraw-held')
            """)
    List<Map<String, Object>> abnormalAccountCategories();

    @Select("""
            SELECT COUNT(DISTINCT canonical.userId)
              FROM (
                    SELECT user_id AS userId
                      FROM nx_risk_signal
                     WHERE is_deleted=0
                       AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                       AND LOWER(signal_type) IN (
                         'risk.multi_account_flagged','risk.multi_account_incident_created',
                         'risk.arbitrage_suspected','risk.leaderboard_velocity_flagged',
                         'risk.trial_cycle_detected','risk.withdraw_held')
                    UNION ALL
                    SELECT user_id
                      FROM nx_withdrawal_order
                     WHERE is_deleted=0 AND UPPER(status)='FROZEN'
              ) canonical
            """)
    long abnormalAccountCount();

    @Select("""
            SELECT gates.gateKey,
                   COALESCE(primary_gate.setting_value, legacy_gate.setting_value) AS settingValue
              FROM (
                    SELECT 'withdraw' AS gateKey
                    UNION ALL SELECT 'staking'
                    UNION ALL SELECT 'genesis'
                    UNION ALL SELECT 'exchange'
                    UNION ALL SELECT 'trial'
              ) gates
              LEFT JOIN nx_emergency_control_setting primary_gate
                ON primary_gate.setting_key=CONCAT('killswitch.', gates.gateKey)
               AND primary_gate.is_deleted=0
              LEFT JOIN nx_emergency_control_setting legacy_gate
                ON legacy_gate.setting_key=CONCAT('J.killswitch.', gates.gateKey)
               AND legacy_gate.is_deleted=0
             ORDER BY FIELD(gates.gateKey,'withdraw','staking','genesis','exchange','trial')
            """)
    List<Map<String, Object>> killSwitchStates();
}
