package ffdd.opsconsole.user.mapper;

import ffdd.opsconsole.finance.mapper.D1BankOrderMapper;
import ffdd.opsconsole.user.domain.UserLatestFundActivityView;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserLatestFundActivityMapper {
    @Select("""
            WITH deposits AS (
              SELECT 'DEPOSIT_ORDER' AS sourceType, d.id AS sourceId, d.deposit_no AS bizNo,
                     d.created_at AS createdAt, d.amount, d.asset AS currency, d.status,
                     NULL AS paymentAmount, NULL AS paymentCurrency
                FROM nx_deposit_order d
               WHERE d.user_id=#{userId} AND d.is_deleted=0
              UNION ALL
              SELECT 'PAYMENT_RECORD', p.id, p.payment_no, p.created_at,
                     p.amount_usdt, 'USDT', p.payment_status, NULL, p.currency
                FROM nx_payment_record p
               WHERE p.user_id=#{userId} AND p.is_deleted=0
                 AND (EXISTS (SELECT 1 FROM nx_topup_card_admission a
                               WHERE a.user_id=p.user_id AND a.order_no=p.order_no AND a.is_deleted=0)
                   OR EXISTS (SELECT 1 FROM nx_wallet_ledger l
                               WHERE l.id=p.wallet_ledger_id AND l.user_id=p.user_id
                                 AND l.biz_no=p.payment_no AND l.biz_type='CARD_TOPUP'
                                 AND l.asset='USDT' AND l.amount=p.amount_usdt
                                 AND l.direction='IN' AND l.status='SUCCESS' AND l.is_deleted=0))
                 AND NOT EXISTS (SELECT 1 FROM nx_deposit_order d
                                  WHERE d.user_id=p.user_id AND d.is_deleted=0
                                    AND d.deposit_no IN (p.payment_no,p.order_no))
              UNION ALL
              SELECT 'CARD_ADMISSION', a.id, a.order_no, a.created_at, a.amount_usdt, 'USDT',
                     CASE WHEN a.decision<>'ALLOWED' THEN 'REJECTED'
                          WHEN a.failure_event_id IS NOT NULL THEN 'FAILED'
                          WHEN a.settlement_event_id IS NOT NULL THEN 'ABNORMAL'
                          WHEN a.expires_at<=#{asOf} THEN 'EXPIRED' ELSE 'PENDING' END,
                     NULL, NULL
                FROM nx_topup_card_admission a
               WHERE a.user_id=#{userId} AND a.is_deleted=0
                 AND NOT EXISTS (SELECT 1 FROM nx_payment_record p
                                  WHERE p.user_id=a.user_id AND p.order_no=a.order_no AND p.is_deleted=0)
                 AND NOT EXISTS (SELECT 1 FROM nx_deposit_order d
                                  WHERE d.user_id=a.user_id AND d.deposit_no=a.order_no AND d.is_deleted=0)
              UNION ALL
              SELECT CASE WHEN i.payment_rail='HDPAY' THEN 'HDPAY' ELSE 'VIETQR' END,
                     i.id, i.intent_no, i.created_at, i.requested_usdt, 'USDT',
            """ + D1BankOrderMapper.DISPLAY_STATUS + """
                     , i.payable_vnd, 'VND'
                FROM nx_vietqr_intent i
                LEFT JOIN nx_hdpay_payin_order h
                  ON i.payment_rail='HDPAY' AND h.merchant_order_id=i.intent_no
               WHERE i.user_id=#{userId} AND i.is_deleted=0
                 AND i.payment_rail IN ('MANUAL','HDPAY') AND i.settlement_target_type='WALLET_TOPUP'
              UNION ALL
              SELECT 'VIETQR_RECONCILIATION', r.id, r.reconciliation_no, r.created_at,
                     COALESCE(r.received_vnd,r.payable_vnd), 'VND', r.status,
                     COALESCE(r.received_vnd,r.payable_vnd), 'VND'
                FROM nx_vietqr_reconciliation r
               WHERE r.user_id=#{userId} AND r.is_deleted=0
                 AND NOT EXISTS (SELECT 1 FROM nx_vietqr_intent i
                                  WHERE i.intent_no=r.intent_no AND i.user_id=r.user_id AND i.is_deleted=0)
            )
            SELECT sourceType, sourceId, bizNo, createdAt, amount, currency, status, paymentAmount, paymentCurrency
              FROM deposits
             ORDER BY createdAt DESC, sourceType DESC, sourceId DESC
             LIMIT 1
            """)
    UserLatestFundActivityView latestDeposit(@Param("userId") Long userId, @Param("asOf") LocalDateTime asOf);

    @Select("""
            SELECT 'WITHDRAWAL' AS sourceType, id AS sourceId, withdrawal_no AS bizNo,
                   created_at AS createdAt, amount, asset AS currency, status,
                   NULL AS paymentAmount, NULL AS paymentCurrency
              FROM nx_withdrawal_order
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """)
    UserLatestFundActivityView latestWithdrawal(@Param("userId") Long userId);
}
