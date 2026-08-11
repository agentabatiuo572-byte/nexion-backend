package ffdd.opsconsole.risk.mapper;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

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
            WITH RECURSIVE days AS (
              SELECT 0 AS seq, DATE(#{startAt}) AS pressureDay
              UNION ALL
              SELECT seq + 1, DATE_ADD(pressureDay, INTERVAL 1 DAY)
                FROM days
               WHERE seq < 7
            ), buckets AS (
              SELECT DATE(created_at) AS pressureDay,
                     COALESCE(SUM(CASE
                       WHEN direction='OUT' AND (
                         UPPER(biz_type) LIKE '%WITHDRAW%'
                         OR UPPER(biz_type) LIKE '%PAYOUT%'
                         OR UPPER(biz_type) LIKE '%COMMISSION%'
                       ) THEN amount ELSE 0 END), 0) AS payoutCommission,
                     COALESCE(SUM(CASE WHEN direction='IN' THEN amount ELSE 0 END), 0) AS grossInflow
                FROM nx_wallet_ledger
               WHERE is_deleted=0
                 AND asset='USDT'
                 AND status='SUCCESS'
                 AND created_at >= #{startAt}
                 AND created_at < #{endAt}
               GROUP BY DATE(created_at)
            )
            SELECT DATE_FORMAT(days.pressureDay, '%m-%d') AS label,
                   CASE
                     WHEN COALESCE(buckets.grossInflow, 0)=0 THEN NULL
                     ELSE ROUND(COALESCE(buckets.payoutCommission, 0) / buckets.grossInflow, 4)
                   END AS ratio
              FROM days
              LEFT JOIN buckets ON buckets.pressureDay=days.pressureDay
             ORDER BY days.pressureDay ASC
            """)
    List<Map<String, Object>> pressureWindows(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT levels.level,
                   COALESCE(buckets.count, 0) AS count
              FROM (
                    SELECT 'P0' AS level, 0 AS sortOrder
                    UNION ALL SELECT 'P1', 1
                    UNION ALL SELECT 'P2', 2
                    UNION ALL SELECT 'P3', 3
              ) levels
              LEFT JOIN (
                    SELECT CASE UPPER(severity)
                             WHEN 'CRITICAL' THEN 'P0'
                             WHEN 'HIGH' THEN 'P1'
                             WHEN 'MEDIUM' THEN 'P2'
                             ELSE 'P3'
                           END AS level,
                           COUNT(1) AS count
                      FROM nx_risk_signal
                     WHERE is_deleted=0
                       AND created_at >= #{startAt}
                       AND created_at < #{endAt}
                     GROUP BY level
              ) buckets ON buckets.level=levels.level
             ORDER BY levels.sortOrder
            """)
    List<Map<String, Object>> alertSeverity(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Select("""
            WITH RECURSIVE days AS (
              SELECT 0 AS seq, DATE(#{startAt}) AS alertDay
              UNION ALL
              SELECT seq + 1, DATE_ADD(alertDay, INTERVAL 1 DAY)
                FROM days
               WHERE seq < 6
            ), buckets AS (
              SELECT DATE(created_at) AS alertDay, COUNT(1) AS count
                FROM nx_risk_signal
               WHERE is_deleted=0
                 AND created_at >= #{startAt}
                 AND created_at < #{endAt}
               GROUP BY DATE(created_at)
            )
            SELECT DATE_FORMAT(days.alertDay, '%m-%d') AS label,
                   COALESCE(buckets.count, 0) AS count
              FROM days
              LEFT JOIN buckets ON buckets.alertDay=days.alertDay
             ORDER BY days.alertDay ASC
            """)
    List<Map<String, Object>> alertVolume(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT s.signal_no AS signalNo,
                   CASE UPPER(severity)
                     WHEN 'CRITICAL' THEN 'P0'
                     WHEN 'HIGH' THEN 'P1'
                     WHEN 'MEDIUM' THEN 'P2'
                     ELSE 'P3'
                   END AS level,
                   s.signal_type AS signalType,
                   s.user_id AS userId,
                   DATE_FORMAT(s.created_at, '%Y-%m-%dT%H:%i:%s') AS createdAt,
                   COALESCE(d.handling_status, 'open') AS handlingStatus,
                   COALESCE(d.version, 0) AS handlingVersion,
                   COALESCE((SELECT CASE WHEN x.receipt_source='mock' THEN 'SANDBOX_DELIVERED'
                                        ELSE x.delivery_status END
                               FROM nx_admin_risk_alert_delivery x
                              WHERE x.signal_no=s.signal_no AND x.is_deleted=0
                              ORDER BY x.id DESC LIMIT 1), 'NOT_QUEUED') AS deliveryStatus
              FROM nx_risk_signal s
              LEFT JOIN nx_admin_risk_signal_disposition d
                ON d.signal_no=s.signal_no AND d.is_deleted=0
             WHERE s.is_deleted=0
               AND s.created_at >= #{startAt}
               AND s.created_at < #{endAt}
             ORDER BY s.created_at DESC, s.id DESC
             LIMIT 20
            """)
    List<Map<String, Object>> recentSignals(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Select("SELECT COUNT(*) FROM nx_risk_signal WHERE signal_no=#{signalNo} AND is_deleted=0")
    int signalExists(@Param("signalNo") String signalNo);

    @Insert("""
            INSERT IGNORE INTO nx_admin_risk_signal_disposition
              (signal_no, handling_status, version, created_at, updated_at, is_deleted)
            VALUES (#{signalNo}, 'open', 0, NOW(), NOW(), 0)
            """)
    int ensureSignalDisposition(@Param("signalNo") String signalNo);

    @Select("""
            SELECT signal_no AS signalNo, handling_status AS status, version
              FROM nx_admin_risk_signal_disposition
             WHERE signal_no=#{signalNo} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    SignalDispositionRecord lockSignalDisposition(@Param("signalNo") String signalNo);

    @Update("""
            UPDATE nx_admin_risk_signal_disposition
               SET handling_status=#{targetStatus}, version=version+1, handled_by=#{actor},
                   reason=#{reason}, handled_at=NOW(), updated_at=NOW()
             WHERE signal_no=#{signalNo} AND handling_status=#{expectedStatus}
               AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updateSignalDisposition(@Param("signalNo") String signalNo,
                                @Param("targetStatus") String targetStatus,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("expectedVersion") long expectedVersion,
                                @Param("actor") String actor,
                                @Param("reason") String reason);

    @Select("""
            SELECT s.signal_no
              FROM nx_risk_signal s
              JOIN nx_config_item c ON c.config_key='risk.alert-subscription.subscriber'
                 AND c.config_value=#{subscriber} AND c.status=1 AND c.is_deleted=0
             WHERE s.is_deleted=0
               AND s.created_at >= c.updated_at
               AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_alert_delivery d
                    WHERE d.signal_no=s.signal_no AND d.subscriber=#{subscriber}
                      AND d.channel=#{channel} AND d.is_deleted=0)
             ORDER BY s.created_at ASC, s.id ASC LIMIT #{limit}
            """)
    List<String> undeliveredSignalNos(@Param("subscriber") String subscriber,
                                      @Param("channel") String channel,
                                      @Param("limit") int limit);

    @Insert("""
            INSERT IGNORE INTO nx_admin_risk_alert_delivery
              (signal_no, subscriber, channel, delivery_status, retry_count, next_retry_at,
               created_at, updated_at, is_deleted)
            VALUES (#{signalNo}, #{subscriber}, #{channel}, 'PENDING', 0, NOW(), NOW(), NOW(), 0)
            """)
    int enqueueAlertDelivery(@Param("signalNo") String signalNo,
                             @Param("subscriber") String subscriber,
                             @Param("channel") String channel);

    @Select("""
            SELECT id, signal_no AS signalNo, subscriber, channel,
                   delivery_status AS deliveryStatus, retry_count AS retryCount,
                   next_retry_at AS nextRetryAt
              FROM nx_admin_risk_alert_delivery
             WHERE is_deleted=0 AND ((delivery_status IN ('PENDING','FAILED_RETRY')
               AND (next_retry_at IS NULL OR next_retry_at <= NOW()))
               OR (delivery_status='SENDING' AND processing_started_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)))
             ORDER BY created_at ASC, id ASC LIMIT #{limit}
            """)
    List<AlertDeliveryRecord> dueAlertDeliveries(@Param("limit") int limit);

    @Update("""
            UPDATE nx_admin_risk_alert_delivery
               SET delivery_status='SENDING', processing_started_at=NOW(), updated_at=NOW()
             WHERE id=#{id} AND is_deleted=0 AND (delivery_status IN ('PENDING','FAILED_RETRY')
                OR (delivery_status='SENDING' AND processing_started_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)))
            """)
    int claimAlertDelivery(@Param("id") long id);

    @Update("""
            UPDATE nx_admin_risk_alert_delivery
               SET delivery_status='DELIVERED', delivered_at=NOW(), last_error=NULL,
                   receipt_source=#{receiptSource}, provider_receipt=#{providerReceipt}, updated_at=NOW()
             WHERE id=#{id} AND delivery_status='SENDING' AND is_deleted=0
            """)
    int markAlertDelivered(@Param("id") long id,
                           @Param("receiptSource") String receiptSource,
                           @Param("providerReceipt") String providerReceipt);

    @Update("""
            UPDATE nx_admin_risk_alert_delivery
               SET delivery_status=CASE WHEN retry_count + 1 >= #{maxRetries} THEN 'DEAD' ELSE 'FAILED_RETRY' END,
                   retry_count=retry_count+1,
                   next_retry_at=CASE WHEN retry_count + 1 >= #{maxRetries} THEN NULL
                     ELSE DATE_ADD(NOW(), INTERVAL LEAST(60, POW(2, retry_count + 1)) MINUTE) END,
                   last_error=#{error}, updated_at=NOW()
             WHERE id=#{id} AND delivery_status='SENDING' AND is_deleted=0
            """)
    int markAlertFailure(@Param("id") long id, @Param("error") String error,
                         @Param("maxRetries") int maxRetries);

    @Select("""
            SELECT id, signal_no AS signalNo, channel, delivery_status AS deliveryStatus,
                   receipt_source AS receiptSource, provider_receipt AS providerReceipt,
                   delivered_at AS deliveredAt, read_at AS readAt, acknowledged_at AS acknowledgedAt
              FROM nx_admin_risk_alert_delivery
             WHERE subscriber=#{subscriber} AND is_deleted=0 AND channel='inApp'
             ORDER BY created_at DESC, id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> subscriberInbox(@Param("subscriber") String subscriber, @Param("limit") int limit);

    @Update("""
            UPDATE nx_admin_risk_alert_delivery SET read_at=COALESCE(read_at,NOW()),
                   acknowledged_at=NOW(), updated_at=NOW()
             WHERE id=#{id} AND subscriber=#{subscriber} AND channel='inApp'
               AND delivery_status='DELIVERED' AND is_deleted=0
            """)
    int acknowledgeInbox(@Param("id") long id, @Param("subscriber") String subscriber);

    @Select("""
            SELECT COUNT(1)
              FROM nx_risk_signal
             WHERE is_deleted=0
               AND created_at >= #{startAt}
               AND created_at < #{endAt}
               AND (severity IS NULL OR UPPER(severity) NOT IN ('CRITICAL','HIGH','MEDIUM','LOW','INFO'))
            """)
    long unknownSeverityCount(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COUNT(1)
              FROM nx_withdrawal_order
             WHERE is_deleted=0
               AND (status IS NULL OR UPPER(status) NOT IN (
                    'PENDING','SUBMITTED','REVIEWING','REVIEW_PENDING','DELAYED','EXTENDED_HOLD','FROZEN',
                    'PENDING_CHAIN','REVIEW_PASSED','PROCESSING','CHAIN_SUBMITTED','SENT','DEAD','TX_ORPHANED',
                    'REVIEW_REJECTED','REJECTED','ADDRESS_INVALID','TX_FAILED','FAILED','COMPLETED','SUCCESS',
                    'CANCELLED','CANCELED','CONFIRMED','REFUNDED'))
            """)
    long unknownWithdrawalStatusCount();

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

    record SignalDispositionRecord(String signalNo, String status, long version) {
    }

    record AlertDeliveryRecord(long id, String signalNo, String subscriber, String channel,
                               String deliveryStatus, int retryCount, LocalDateTime nextRetryAt) {
    }
}
