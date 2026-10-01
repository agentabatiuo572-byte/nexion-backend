package ffdd.opsconsole.finance.mapper;

/** Read-only facts: one committed ledger row, linked to an actual deposit source. */
final class SupportFinanceSql {
    private SupportFinanceSql() {}
    static final String CARD_SCOPE="""
      (EXISTS(SELECT 1 FROM nx_topup_card_admission a WHERE a.user_id=p.user_id AND a.order_no=p.order_no AND a.is_deleted=0)
       OR EXISTS(SELECT 1 FROM nx_wallet_ledger c WHERE c.id=p.wallet_ledger_id AND c.user_id=p.user_id
        AND c.biz_no=p.payment_no AND c.biz_type='CARD_TOPUP' AND c.amount=p.amount_usdt
        AND c.asset='USDT' AND c.direction='IN' AND c.status='SUCCESS' AND c.is_deleted=0))
      """;
    static final String CREDIT_MATCH="""
      ((l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP') AND EXISTS (
        SELECT 1 FROM nx_deposit_order d WHERE d.is_deleted=0 AND d.user_id=l.user_id
          AND d.ledger_id=l.id AND d.deposit_no=l.biz_no AND d.asset=l.asset AND d.amount=l.amount))
       OR (l.biz_type='CARD_TOPUP' AND l.asset='USDT' AND EXISTS (
        SELECT 1 FROM nx_payment_record p WHERE p.is_deleted=0 AND p.user_id=l.user_id
          AND p.wallet_ledger_id=l.id AND p.payment_no=l.biz_no AND p.amount_usdt=l.amount))
       OR (l.biz_type='VIETQR_DEPOSIT' AND l.asset='USDT' AND EXISTS (
        SELECT 1 FROM nx_vietqr_reconciliation r WHERE r.is_deleted=0 AND r.user_id=l.user_id
          AND CONCAT('D1-VIETQR-',r.reconciliation_no)=l.biz_no AND r.credited_usdt=l.amount))
       OR (l.biz_type='VIETQR_DEPOSIT' AND l.asset='USDT' AND EXISTS (
        SELECT 1 FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id
          WHERE i.is_deleted=0 AND i.user_id=l.user_id AND i.payment_rail='HDPAY'
          AND i.settlement_target_type='WALLET_TOPUP' AND h.merchant_order_id=l.biz_no
          AND h.wallet_ledger_biz_no=l.biz_no AND h.settled_at IS NOT NULL
          AND h.settled_usdt=l.amount AND i.credited_usdt=l.amount AND i.requested_usdt=l.amount
          AND i.payable_vnd=h.amount_vnd AND i.received_vnd=h.amount_vnd)))
      """;
    static final String CREDITS="""
      SELECT l.id,l.user_id,l.biz_no,l.asset,l.amount,l.created_at FROM nx_wallet_ledger l
      WHERE l.user_id=#{userId} AND l.is_deleted=0 AND l.direction='IN' AND l.status='SUCCESS'
        AND l.amount>0 AND
      """+CREDIT_MATCH;

    static final String FLOWS="""
      WITH flows AS (
       SELECT CONCAT('DEPOSIT:',d.id) sourceId,d.deposit_no bizNo,'DEPOSIT' kind,d.asset currency,
         d.amount principal,NULL fee,CASE WHEN l.id IS NOT NULL THEN l.amount END net,
         CASE WHEN d.status IN ('CONFIRMED','CREDITED','SUCCESS') AND l.id IS NULL THEN 'ABNORMAL'
           WHEN p.payment_status IS NOT NULL THEN p.payment_status ELSE d.status END status,
         d.created_at createdAt,d.credited_at completedAt,NULL paymentCurrency,NULL paymentAmount
       FROM nx_deposit_order d LEFT JOIN nx_wallet_ledger l ON l.id=d.ledger_id AND l.is_deleted=0
         AND l.user_id=d.user_id AND l.biz_no=d.deposit_no AND l.asset=d.asset AND l.amount=d.amount
         AND l.direction='IN' AND l.status='SUCCESS' AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP','CARD_TOPUP')
       LEFT JOIN (SELECT user_id,wallet_ledger_id,payment_no,amount_usdt,
         CASE WHEN COUNT(DISTINCT payment_status)>1 THEN 'ABNORMAL' ELSE MAX(payment_status) END payment_status
         FROM nx_payment_record WHERE user_id=#{userId} AND is_deleted=0
         GROUP BY user_id,wallet_ledger_id,payment_no,amount_usdt) p ON p.wallet_ledger_id=d.ledger_id AND p.user_id=d.user_id
         AND p.payment_no=d.deposit_no AND p.amount_usdt=d.amount AND d.asset='USDT'
       WHERE d.user_id=#{userId} AND d.is_deleted=0
       UNION ALL
       SELECT CONCAT('CARD:',p.id),p.payment_no,'DEPOSIT','USDT',p.amount_usdt,NULL,l.amount,
         CASE WHEN p.payment_status IN ('CONFIRMED','CREDITED','SUCCESS') AND l.id IS NULL THEN 'ABNORMAL' ELSE p.payment_status END,
         p.created_at,l.created_at,p.currency,NULL
       FROM nx_payment_record p LEFT JOIN nx_wallet_ledger l ON l.id=p.wallet_ledger_id
         AND l.user_id=p.user_id AND l.biz_no=p.payment_no AND l.biz_type='CARD_TOPUP'
         AND l.asset='USDT' AND l.amount=p.amount_usdt AND l.direction='IN' AND l.status='SUCCESS' AND l.is_deleted=0
       WHERE p.user_id=#{userId} AND p.is_deleted=0 AND
      """+CARD_SCOPE+"""
        AND NOT EXISTS (
         SELECT 1 FROM nx_deposit_order d WHERE d.user_id=p.user_id AND d.is_deleted=0
           AND d.ledger_id=p.wallet_ledger_id AND d.deposit_no=p.payment_no AND d.asset='USDT' AND d.amount=p.amount_usdt)
       UNION ALL
       SELECT CONCAT('CARD_ADMISSION:',a.id),a.order_no,'DEPOSIT','USDT',a.amount_usdt,NULL,NULL,
         CASE WHEN a.decision!='ALLOWED' THEN 'REJECTED' WHEN a.expires_at&lt;=NOW(6) THEN 'EXPIRED' ELSE 'PENDING' END,
         a.created_at,NULL,NULL,NULL
       FROM nx_topup_card_admission a WHERE a.user_id=#{userId} AND a.is_deleted=0
         AND a.settlement_event_id IS NULL AND a.failure_event_id IS NULL
         AND NOT EXISTS(SELECT 1 FROM nx_payment_record p WHERE p.user_id=a.user_id AND p.order_no=a.order_no AND p.is_deleted=0)
         AND NOT EXISTS(SELECT 1 FROM nx_deposit_order d WHERE d.user_id=a.user_id AND d.deposit_no=a.order_no AND d.is_deleted=0)
       UNION ALL
       SELECT CONCAT('VIETQR:',r.id),r.reconciliation_no,'DEPOSIT','USDT',r.credited_usdt,NULL,l.amount,
         CASE WHEN r.status='CREDITED' AND l.id IS NULL THEN 'ABNORMAL' ELSE r.status END,r.created_at,l.created_at,'VND',r.received_vnd
       FROM nx_vietqr_reconciliation r LEFT JOIN nx_wallet_ledger l ON l.user_id=r.user_id
         AND l.biz_no=CONCAT('D1-VIETQR-',r.reconciliation_no) AND l.biz_type='VIETQR_DEPOSIT'
         AND l.amount=r.credited_usdt AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS' AND l.is_deleted=0
       WHERE r.user_id=#{userId} AND r.is_deleted=0 AND NOT EXISTS (
         SELECT 1 FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id
         WHERE i.user_id=r.user_id AND i.intent_no=r.intent_no AND i.payment_rail='HDPAY'
          AND i.settlement_target_type='WALLET_TOPUP' AND i.is_deleted=0
          AND h.settled_at IS NOT NULL AND h.settled_usdt=r.credited_usdt AND h.wallet_ledger_biz_no=i.intent_no
          AND i.requested_usdt=h.settled_usdt AND i.credited_usdt=h.settled_usdt
          AND i.payable_vnd=h.amount_vnd AND i.received_vnd=h.amount_vnd
          AND EXISTS(SELECT 1 FROM nx_wallet_ledger hl WHERE hl.user_id=i.user_id AND hl.biz_no=i.intent_no
           AND hl.biz_type='VIETQR_DEPOSIT' AND hl.asset='USDT' AND hl.direction='IN' AND hl.status='SUCCESS'
           AND hl.is_deleted=0 AND hl.amount=h.settled_usdt)
          AND NOT EXISTS(SELECT 1 FROM nx_wallet_ledger ml WHERE ml.user_id=r.user_id
           AND ml.biz_no=CONCAT('D1-VIETQR-',r.reconciliation_no) AND ml.biz_type='VIETQR_DEPOSIT'
           AND ml.asset='USDT' AND ml.direction='IN' AND ml.status='SUCCESS' AND ml.is_deleted=0 AND ml.amount=r.credited_usdt))
       UNION ALL
       SELECT CONCAT('HDPAY:',h.id),i.intent_no,'DEPOSIT','USDT',i.requested_usdt,NULL,l.amount,
         CASE WHEN (h.settlement_status='CREDITED' OR i.status='CREDITED') AND l.id IS NULL THEN 'ABNORMAL' ELSE h.settlement_status END,i.created_at,h.settled_at,'VND',h.amount_vnd
       FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id
       LEFT JOIN nx_wallet_ledger l ON l.user_id=i.user_id AND l.biz_no=i.intent_no
         AND l.biz_type='VIETQR_DEPOSIT' AND l.asset='USDT' AND l.amount=h.settled_usdt
         AND l.direction='IN' AND l.status='SUCCESS' AND l.is_deleted=0
         AND h.wallet_ledger_biz_no=l.biz_no AND h.settled_at IS NOT NULL AND i.credited_usdt=l.amount
         AND i.requested_usdt=l.amount AND i.payable_vnd=h.amount_vnd AND i.received_vnd=h.amount_vnd
       WHERE i.user_id=#{userId} AND i.is_deleted=0 AND i.payment_rail='HDPAY' AND i.settlement_target_type='WALLET_TOPUP'
       UNION ALL
       SELECT CONCAT('WITHDRAWAL:',w.id),w.withdrawal_no,'WITHDRAWAL',w.asset,w.amount,w.d2_actual_fee,w.d2_net_receive,
         w.status,w.created_at,w.completed_at,CASE WHEN w.chain='BANK-VND' THEN 'VND' END,q.amount_vnd
       FROM nx_withdrawal_order w LEFT JOIN nx_bank_payout_quote q ON q.withdrawal_no=w.withdrawal_no AND q.user_id=w.user_id
       WHERE w.user_id=#{userId} AND w.is_deleted=0
       UNION ALL
       SELECT CONCAT('LEDGER:',l.id),l.biz_no,l.biz_type,l.asset,l.amount,NULL,NULL,
         CASE WHEN l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP','CARD_TOPUP','VIETQR_DEPOSIT') THEN 'ABNORMAL' ELSE l.status END,l.created_at,l.created_at,NULL,NULL
       FROM nx_wallet_ledger l WHERE l.user_id=#{userId} AND l.is_deleted=0
         AND (l.biz_type NOT IN ('CHAIN_TOPUP','DEPOSIT','TOPUP','CARD_TOPUP','VIETQR_DEPOSIT') OR NOT
      """+CREDIT_MATCH+"""
         )
         AND (l.asset&lt;&gt;'USDT' OR l.biz_type NOT IN ('WITHDRAW','WITHDRAW_PAYOUT','WITHDRAW_NET_PRINCIPAL','WITHDRAW_NETWORK_FEE','WITHDRAW_PENALTY_FEE','WITHDRAW_BANK_FEE','WITHDRAW_RESERVE'))
      )
      """;
    static final String FLOW_FILTER="""
      WHERE 1=1 <if test='currency!=null'>AND currency=#{currency}</if>
       <if test='status!=null'>AND status=#{status}</if>
       <if test='from!=null'>AND createdAt &gt;= #{from}</if>
       <if test='to!=null'>AND createdAt &lt; #{to}</if>
      """;
}
