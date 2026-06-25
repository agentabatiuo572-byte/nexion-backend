package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.infrastructure.PaymentRecordEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentRecordMapper extends BaseMapper<PaymentRecordEntity> {
    @Select("""
            SELECT COUNT(1)
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND payment_no LIKE 'D1-SEED-%'
               AND created_at >= CURRENT_DATE
            """)
    long countD1SeedPaymentsToday();

    @Insert("""
            INSERT INTO nx_payment_record (
                payment_no, order_no, user_id, provider, provider_payment_id, amount_usdt, currency,
                payment_status, checkout_url, expires_at, callback_event_id, signature_status, raw_callback,
                paid_at, failed_at, failure_reason, reconcile_attempts, last_reconcile_at, next_reconcile_at,
                last_reconcile_error, expired_at, created_at, updated_at, is_deleted
            )
            VALUES (
                #{paymentNo}, #{orderNo}, #{userId}, #{provider}, #{providerPaymentId}, #{amountUsdt}, #{currency},
                #{paymentStatus}, NULL, DATE_ADD(NOW(), INTERVAL 30 MINUTE), #{callbackEventId}, #{signatureStatus}, NULL,
                CASE WHEN #{paid} THEN DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE) ELSE NULL END,
                CASE WHEN #{failed} THEN DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE) ELSE NULL END,
                #{failureReason}, #{reconcileAttempts},
                CASE WHEN #{reconciled} THEN DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE) ELSE NULL END,
                NULL, #{lastReconcileError},
                CASE WHEN #{expired} THEN DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE) ELSE NULL END,
                DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE), DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE), 0
            )
            ON DUPLICATE KEY UPDATE
                order_no = VALUES(order_no),
                user_id = VALUES(user_id),
                provider = VALUES(provider),
                provider_payment_id = VALUES(provider_payment_id),
                amount_usdt = VALUES(amount_usdt),
                currency = VALUES(currency),
                payment_status = VALUES(payment_status),
                callback_event_id = VALUES(callback_event_id),
                signature_status = VALUES(signature_status),
                paid_at = VALUES(paid_at),
                failed_at = VALUES(failed_at),
                failure_reason = VALUES(failure_reason),
                reconcile_attempts = VALUES(reconcile_attempts),
                last_reconcile_at = VALUES(last_reconcile_at),
                last_reconcile_error = VALUES(last_reconcile_error),
                expired_at = VALUES(expired_at),
                created_at = VALUES(created_at),
                updated_at = VALUES(updated_at),
                is_deleted = 0
            """)
    int insertD1SeedPayment(@Param("paymentNo") String paymentNo,
                            @Param("orderNo") String orderNo,
                            @Param("userId") Long userId,
                            @Param("provider") String provider,
                            @Param("providerPaymentId") String providerPaymentId,
                            @Param("amountUsdt") BigDecimal amountUsdt,
                            @Param("currency") String currency,
                            @Param("paymentStatus") String paymentStatus,
                            @Param("callbackEventId") String callbackEventId,
                            @Param("signatureStatus") String signatureStatus,
                            @Param("paid") boolean paid,
                            @Param("failed") boolean failed,
                            @Param("expired") boolean expired,
                            @Param("failureReason") String failureReason,
                            @Param("reconcileAttempts") int reconcileAttempts,
                            @Param("reconciled") boolean reconciled,
                            @Param("lastReconcileError") String lastReconcileError,
                            @Param("minutesAgo") int minutesAgo);

    @Select("""
            SELECT COUNT(1)
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND created_at >= CURRENT_DATE
               AND provider IN ('Checkout.com', 'Stripe', 'Card')
               AND payment_status IN ('PAID', 'SUCCESS', 'CONFIRMED')
            """)
    long cardPaidCountToday();

    @Select("""
            SELECT COALESCE(SUM(amount_usdt), 0)
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND created_at >= CURRENT_DATE
               AND provider IN ('Checkout.com', 'Stripe', 'Card')
               AND payment_status IN ('PAID', 'SUCCESS', 'CONFIRMED')
            """)
    BigDecimal cardPaidAmountToday();

    @Select("""
            SELECT CONCAT('PSP ', provider, ' · ', COALESCE(NULLIF(signature_status, ''), 'NO_SIG')) AS segment,
                   CONCAT(COALESCE(NULLIF(failure_reason, ''), payment_status), ' · ', COUNT(1), ' attempts') AS meta,
                   COUNT(1) AS fails24h,
                   CASE WHEN COUNT(1) >= #{threshold} THEN TRUE ELSE FALSE END AS locked,
                   CASE WHEN COUNT(1) >= #{threshold} THEN '自动锁定候选' ELSE '观察中' END AS note,
                   FALSE AS manual
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
               AND payment_status IN ('FAILED', 'DECLINED', 'EXPIRED')
             GROUP BY provider, signature_status, failure_reason, payment_status
             ORDER BY fails24h DESC, segment ASC
             LIMIT 20
            """)
    List<DepositBinRiskView> failedPaymentRiskRows(@Param("threshold") int threshold);

    @Select("""
            SELECT payment_no AS caseNo,
                   user_id AS userId,
                   CONCAT('usr_', user_id) AS userCode,
                   amount_usdt AS amount,
                   COALESCE(NULLIF(failure_reason, ''), 'chargeback') AS reasonCode,
                   '已入账' AS enteredStatus,
                   payment_status AS status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND payment_status IN ('CHARGEBACK', 'DISPUTED', 'CHARGEBACK_REVIEW', 'CHARGEBACK_REFUNDED')
             ORDER BY updated_at DESC, id DESC
             LIMIT 50
            """)
    List<DepositChargebackView> chargebacks();

    @Select("""
            SELECT payment_no AS caseNo,
                   user_id AS userId,
                   CONCAT('usr_', user_id) AS userCode,
                   amount_usdt AS amount,
                   COALESCE(NULLIF(failure_reason, ''), 'chargeback') AS reasonCode,
                   '已入账' AS enteredStatus,
                   payment_status AS status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM nx_payment_record
             WHERE is_deleted = 0
               AND payment_no = #{caseNo}
               AND payment_status IN ('CHARGEBACK', 'DISPUTED', 'CHARGEBACK_REVIEW', 'CHARGEBACK_REFUNDED')
             LIMIT 1
            """)
    DepositChargebackView findChargeback(@Param("caseNo") String caseNo);

    @Update("""
            UPDATE nx_payment_record
               SET payment_status = 'CHARGEBACK_REFUNDED',
                   failure_reason = LEFT(CONCAT(COALESCE(failure_reason, ''), ' | refund: ', #{reason}), 255),
                   updated_at = CURRENT_TIMESTAMP
             WHERE is_deleted = 0
               AND payment_no = #{caseNo}
               AND payment_status IN ('CHARGEBACK', 'DISPUTED', 'CHARGEBACK_REVIEW')
            """)
    int markChargebackRefunded(@Param("caseNo") String caseNo, @Param("reason") String reason);
}
