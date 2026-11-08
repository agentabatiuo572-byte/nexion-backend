package ffdd.opsconsole.overview.mapper;

import ffdd.opsconsole.finance.mapper.D1HdPayReadSql;
import ffdd.opsconsole.shared.config.BusinessPeriodSql;

/** Committed business facts only. Wallet purchases and treasury injections are never external deposits. */
public final class BusinessSummarySql {
    private BusinessSummarySql() {}

    public static final String DATE = "DATE(" + BusinessPeriodSql.Legacy.BUSINESS_TIME_HEAD + "occurred_at"
            + BusinessPeriodSql.Legacy.BUSINESS_TIME_MID + "occurred_at"
            + BusinessPeriodSql.Legacy.BUSINESS_TIME_TAIL + ")";

    public static final String CTES = D1HdPayReadSql.CTES + """
            , deposits AS (
                SELECT l.user_id, l.created_at AS occurred_at, l.amount,
                       CASE WHEN l.biz_type='CARD_TOPUP' THEN 'CARD'
                            WHEN UPPER(d.chain_name) IN ('CREGIS_USDT_BEP20','BEP20','BSC','BNB SMART CHAIN') THEN 'BEP20'
                            WHEN UPPER(d.chain_name) IN ('CREGIS_USDT_ERC20','ERC20','ETH','ETHEREUM') THEN 'ERC20'
                            WHEN UPPER(d.chain_name) IN ('CREGIS_USDT_TRC20','TRC20','TRON') THEN 'TRC20'
                            ELSE 'OTHER' END AS channel
                  FROM nx_wallet_ledger l
                  LEFT JOIN nx_deposit_order d ON d.ledger_id=l.id AND d.user_id=l.user_id
                   AND d.deposit_no=l.biz_no AND d.asset=l.asset AND d.amount=l.amount
                   AND d.is_deleted=0 AND d.status IN ('CONFIRMED','CREDITED','SUCCESS')
                 WHERE l.is_deleted=0 AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS'
                   AND l.amount>0
                   AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=l.user_id AND u.sandbox=1)
                   AND ((l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP') AND d.id IS NOT NULL)
                     OR (l.biz_type='CARD_TOPUP' AND EXISTS (
                       SELECT 1 FROM nx_payment_record p WHERE p.wallet_ledger_id=l.id AND p.user_id=l.user_id
                        AND p.payment_no=l.biz_no AND p.amount_usdt=l.amount AND p.is_deleted=0
                        AND p.payment_status IN ('PAID','CONFIRMED','CREDITED','SUCCESS'))))
                UNION ALL
                SELECT h.user_id,h.ledger_created_at,h.settled_usdt,'BANK' FROM hdpay_credited h
                 WHERE NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=h.user_id AND u.sandbox=1)
                UNION ALL
                SELECT l.user_id,l.created_at,l.amount,'BANK'
                  FROM nx_wallet_ledger l
                 WHERE l.is_deleted=0 AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS'
                   AND l.biz_type='VIETQR_DEPOSIT' AND l.amount>0
                   AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=l.user_id AND u.sandbox=1)
                   AND EXISTS (SELECT 1 FROM nx_vietqr_reconciliation r
                     WHERE r.user_id=l.user_id AND CONCAT('D1-VIETQR-',r.reconciliation_no)=l.biz_no
                       AND r.credited_usdt=l.amount AND r.status='CREDITED' AND r.is_deleted=0
                       AND NOT EXISTS (SELECT 1 FROM hdpay_credited h
                         WHERE h.intent_no=r.intent_no AND h.user_id=r.user_id))
                UNION ALL
                SELECT p.user_id,p.confirmed_at,
                       CAST(JSON_UNQUOTE(JSON_EXTRACT(p.quote_json,'$.subtotalUsdt')) AS DECIMAL(24,6)),'DIRECT'
                  FROM nx_purchase_payment_intent p
                 WHERE p.confirmed_at IS NOT NULL AND p.receipt_json IS NOT NULL
                   AND p.payment_method IN ('HDPAY_BANK','CREGIS_USDT_BSC','CREGIS_USDT_ERC20','CREGIS_USDT_TRC20')
                   AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=p.user_id AND u.sandbox=1)
            ), sales AS (
                SELECT o.user_id,COALESCE(o.paid_at,o.created_at) AS occurred_at,o.amount_usdt AS gross,
                       CASE WHEN o.order_status='REFUNDED' THEN COALESCE(o.refund_amount_usdt,
                         (SELECT SUM(l.amount) FROM nx_wallet_ledger l
                           WHERE l.user_id=o.user_id AND l.biz_no=CONCAT('E4-REFUND-',o.order_no)
                             AND l.biz_type='ORDER_REFUND' AND l.asset='USDT' AND l.direction='IN'
                             AND l.status='SUCCESS' AND l.is_deleted=0),
                         (SELECT SUM(b.amount) FROM nx_wallet_bill b
                           WHERE b.user_id=o.user_id AND b.bill_no=CONCAT('E4-BILL-',o.order_no)
                             AND b.type='ORDER_REFUND' AND b.token='USDT' AND b.direction='IN' AND b.deleted=0))
                         ELSE 0 END AS refunded
                  FROM nx_order o
                 WHERE o.is_deleted=0 AND o.amount_usdt>0
                   AND ((o.payment_status='PAID' AND o.order_status='COMPLETED')
                     OR (o.payment_status='REFUNDED' AND o.order_status='REFUNDED'))
                   AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=o.user_id AND u.sandbox=1)
            ), facts AS (
                SELECT user_id,occurred_at,CONCAT('deposit_',channel) AS metric,amount AS value FROM deposits
                UNION ALL SELECT user_id,occurred_at,'depositCount',1 FROM deposits
                UNION ALL SELECT w.user_id,w.completed_at,
                    CASE WHEN w.chain='BANK-VND' THEN 'withdrawalVnd' ELSE 'withdrawalUsdt' END,w.amount
                  FROM nx_withdrawal_order w
                 WHERE w.is_deleted=0 AND w.asset='USDT' AND w.status IN ('CONFIRMED','SUCCESS')
                   AND w.completed_at IS NOT NULL
                   AND (w.d5_payout_source IS NULL OR w.d5_payout_source NOT IN ('mock','dev-simulator'))
                   AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=w.user_id AND u.sandbox=1)
                UNION ALL SELECT w.user_id,w.completed_at,'withdrawalFees',COALESCE(w.d2_actual_fee,w.fee,0)
                  FROM nx_withdrawal_order w
                 WHERE w.is_deleted=0 AND w.asset='USDT' AND w.status IN ('CONFIRMED','SUCCESS')
                   AND w.completed_at IS NOT NULL
                   AND (w.d5_payout_source IS NULL OR w.d5_payout_source NOT IN ('mock','dev-simulator'))
                   AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=w.user_id AND u.sandbox=1)
                UNION ALL SELECT w.user_id,w.completed_at,'withdrawalCount',1
                  FROM nx_withdrawal_order w
                 WHERE w.is_deleted=0 AND w.asset='USDT' AND w.status IN ('CONFIRMED','SUCCESS')
                   AND w.completed_at IS NOT NULL
                   AND (w.d5_payout_source IS NULL OR w.d5_payout_source NOT IN ('mock','dev-simulator'))
                   AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=w.user_id AND u.sandbox=1)
                UNION ALL SELECT user_id,occurred_at,'salesGross',gross FROM sales
                UNION ALL SELECT user_id,occurred_at,'salesRefunds',COALESCE(refunded,0) FROM sales
                UNION ALL SELECT user_id,occurred_at,'salesRefundUnknown',1 FROM sales WHERE refunded IS NULL
                UNION ALL SELECT user_id,occurred_at,'salesCount',CASE WHEN gross>refunded THEN 1 ELSE 0 END FROM sales
                UNION ALL SELECT id,created_at,'registered',1 FROM nx_user WHERE is_deleted=0 AND sandbox=0
                UNION ALL SELECT d.user_id,MIN(d.occurred_at),'firstDeposit',1
                  FROM deposits d JOIN nx_user u ON u.id=d.user_id AND u.is_deleted=0 AND u.sandbox=0 GROUP BY d.user_id
                UNION ALL SELECT s.user_id,MIN(s.occurred_at),'firstPurchase',1
                  FROM sales s JOIN nx_user u ON u.id=s.user_id AND u.is_deleted=0 AND u.sandbox=0
                 WHERE s.gross>s.refunded GROUP BY s.user_id
            )
            """;
}
