package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.infrastructure.PaymentRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PaymentRecordMapper extends BaseMapper<PaymentRecordEntity> {

    @Select("""
            SELECT CONCAT(f.target_type, ':', f.target_value) AS segment,
                   CONCAT(f.target_type, ' · ', f.target_value) AS meta,
                   f.fails24h AS fails24h,
                   CASE WHEN l.status = 'ACTIVE' AND l.locked_until > NOW() THEN TRUE ELSE FALSE END AS locked,
                   CASE WHEN l.status = 'ACTIVE' AND l.locked_until > NOW()
                        THEN CONCAT('锁定至 ', DATE_FORMAT(l.locked_until, '%Y-%m-%d %H:%i'))
                        ELSE '观察中' END AS note,
                   CASE WHEN l.source = 'MANUAL' THEN TRUE ELSE FALSE END AS manual
              FROM (
                    SELECT 'BIN' AS target_type, card_bin AS target_value, COUNT(1) AS fails24h
                      FROM nx_payment_record
                     WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                       AND payment_status IN ('FAILED', 'DECLINED', 'EXPIRED')
                       AND card_bin IS NOT NULL AND card_bin <> ''
                     GROUP BY card_bin
                    UNION ALL
                    SELECT 'IP', client_ip, COUNT(1)
                      FROM nx_payment_record
                     WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                       AND payment_status IN ('FAILED', 'DECLINED', 'EXPIRED')
                       AND client_ip IS NOT NULL AND client_ip <> ''
                     GROUP BY client_ip
                    UNION ALL
                    SELECT 'DEVICE', device_fingerprint, COUNT(1)
                      FROM nx_payment_record
                     WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                       AND payment_status IN ('FAILED', 'DECLINED', 'EXPIRED')
                       AND device_fingerprint IS NOT NULL AND device_fingerprint <> ''
                     GROUP BY device_fingerprint
              ) f
              LEFT JOIN nx_topup_risk_lock l
                ON l.target_type = f.target_type AND l.target_value = f.target_value AND l.is_deleted = 0
             ORDER BY locked DESC, f.fails24h DESC, segment ASC
             LIMIT 20
            """)
    List<DepositBinRiskView> failedPaymentRiskRows(@Param("threshold") int threshold);

    @Select("""
            SELECT CONCAT(target_type, ':', target_value) AS segment,
                   CONCAT(target_type, ' · ', target_value) AS meta,
                   0 AS fails24h,
                   TRUE AS locked,
                   CONCAT(CASE WHEN source='MANUAL' THEN '手动' ELSE '自动' END,
                          '锁定至 ', DATE_FORMAT(locked_until, '%Y-%m-%d %H:%i')) AS note,
                   CASE WHEN source='MANUAL' THEN TRUE ELSE FALSE END AS manual
              FROM nx_topup_risk_lock
             WHERE is_deleted = 0 AND status='ACTIVE' AND locked_until > NOW()
             ORDER BY updated_at DESC
             LIMIT 100
            """)
    List<DepositBinRiskView> activeRiskLocks();

    @Select("""
            SELECT payment_no AS caseNo,
                   user_id AS userId,
                   CONCAT('usr_', user_id) AS userCode,
                   amount_usdt AS amount,
                   fee_amount_usdt AS feeBufferRequired,
                   COALESCE(NULLIF(failure_reason, ''), 'chargeback') AS reasonCode,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM nx_wallet_ledger l
                        WHERE l.id = nx_payment_record.wallet_ledger_id
                          AND l.is_deleted = 0 AND l.user_id = nx_payment_record.user_id
                          AND l.asset = 'USDT' AND l.direction = 'IN' AND l.status = 'SUCCESS'
                          AND l.biz_type = 'CARD_TOPUP' AND l.biz_no = nx_payment_record.payment_no
                          AND l.amount = nx_payment_record.amount_usdt
                   ) THEN '已入账' ELSE '未找到入账分录' END AS enteredStatus,
                   payment_status AS status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND payment_status IN ('CHARGEBACK', 'DISPUTED', 'CHARGEBACK_REVIEW', 'CHARGEBACK_REFUNDED',
                                      'CHARGEBACK_RECOVERED', 'CHARGEBACK_PARTIAL')
             ORDER BY updated_at DESC, id DESC
             LIMIT 50
            """)
    List<DepositChargebackView> chargebacks();

    @Select("""
            SELECT payment_no AS caseNo,
                   user_id AS userId,
                   CONCAT('usr_', user_id) AS userCode,
                   amount_usdt AS amount,
                   fee_amount_usdt AS feeBufferRequired,
                   COALESCE(NULLIF(failure_reason, ''), 'chargeback') AS reasonCode,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM nx_wallet_ledger l
                        WHERE l.id = nx_payment_record.wallet_ledger_id
                          AND l.is_deleted = 0 AND l.user_id = nx_payment_record.user_id
                          AND l.asset = 'USDT' AND l.direction = 'IN' AND l.status = 'SUCCESS'
                          AND l.biz_type = 'CARD_TOPUP' AND l.biz_no = nx_payment_record.payment_no
                          AND l.amount = nx_payment_record.amount_usdt
                   ) THEN '已入账' ELSE '未找到入账分录' END AS enteredStatus,
                   payment_status AS status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND payment_no = #{caseNo}
               AND payment_status IN ('CHARGEBACK', 'DISPUTED', 'CHARGEBACK_REVIEW', 'CHARGEBACK_REFUNDED',
                                      'CHARGEBACK_RECOVERED', 'CHARGEBACK_PARTIAL')
             LIMIT 1
            """)
    DepositChargebackView findChargeback(@Param("caseNo") String caseNo);

    @Select("""
            SELECT payment_no AS caseNo,
                   user_id AS userId,
                   CONCAT('usr_', user_id) AS userCode,
                   amount_usdt AS amount,
                   fee_amount_usdt AS feeBufferRequired,
                   COALESCE(NULLIF(failure_reason, ''), 'chargeback') AS reasonCode,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM nx_wallet_ledger l
                        WHERE l.id = nx_payment_record.wallet_ledger_id
                          AND l.is_deleted = 0 AND l.user_id = nx_payment_record.user_id
                          AND l.asset = 'USDT' AND l.direction = 'IN' AND l.status = 'SUCCESS'
                          AND l.biz_type = 'CARD_TOPUP' AND l.biz_no = nx_payment_record.payment_no
                          AND l.amount = nx_payment_record.amount_usdt
                   ) THEN '已入账' ELSE '未找到入账分录' END AS enteredStatus,
                   payment_status AS status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM nx_payment_record
             WHERE is_deleted = 0 AND payment_no = #{caseNo}
               AND payment_status IN ('CHARGEBACK', 'DISPUTED', 'CHARGEBACK_REVIEW', 'CHARGEBACK_REFUNDED')
             LIMIT 1
             FOR UPDATE
            """)
    DepositChargebackView findChargebackForUpdate(@Param("caseNo") String caseNo);

}
