package ffdd.opsconsole.finance.mapper;

/** Only source SELECTs. Item/device multiplicity must never multiply payment amounts. */
final class SupportPaymentFactSql {
    private SupportPaymentFactSql() {}
    static final String USERS = " <foreach collection='customerIds' item='customer' open='(' separator=',' close=')'>#{customer}</foreach> ";
    static final String LEDGER = """
        l.id ledgerId,l.user_id ledgerCustomerId,l.biz_no ledgerBusinessId,l.biz_type ledgerType,
        l.asset ledgerCurrency,l.amount ledgerAmount,l.direction ledgerDirection,l.status ledgerStatus,
        l.is_deleted ledgerDeleted,l.created_at ledgerRecordedAt,
        CASE WHEN u.sandbox=1 THEN 1 ELSE 0 END excludedEnvironment
        """;
    static final String DEPOSITS = """
        <script>SELECT 'DEPOSIT' kind,'DEPOSIT_ORDER' source,CONCAT('nx_deposit_order:',d.id) sourceId,
        d.user_id customerId,d.deposit_no businessId,d.deposit_no duplicateKey,d.amount amount,d.asset currency,
        d.credited_at succeededAt,'nx_deposit_order.credited_at' successTimeField,0 fractionalSecondDigits,
        NULL providerPaidAt,NULL sourceVersion,1 sourceLinked,
        """ + LEDGER + """
        FROM nx_deposit_order d LEFT JOIN nx_wallet_ledger l ON l.id=d.ledger_id
        LEFT JOIN nx_user u ON u.id=d.user_id
        WHERE d.is_deleted=0 AND d.user_id IN
        """ + USERS + """
        AND (d.ledger_id IS NOT NULL OR d.status IN ('CONFIRMED','CREDITED','SUCCESS'))</script>
        """;
    static final String CARDS = """
        <script>SELECT 'DEPOSIT' kind,'CARD_TOPUP' source,CONCAT('nx_payment_record:',p.id) sourceId,
        p.user_id customerId,p.payment_no businessId,p.payment_no duplicateKey,p.amount_usdt amount,'USDT' currency,
        l.created_at succeededAt,'nx_wallet_ledger.created_at' successTimeField,0 fractionalSecondDigits,
        p.paid_at providerPaidAt,NULL sourceVersion,1 sourceLinked,
        l.id ledgerId,l.user_id ledgerCustomerId,l.biz_no ledgerBusinessId,l.biz_type ledgerType,
        l.asset ledgerCurrency,l.amount ledgerAmount,l.direction ledgerDirection,l.status ledgerStatus,l.is_deleted ledgerDeleted,
        l.created_at ledgerRecordedAt,
        CASE WHEN u.sandbox=1 OR p.provider IN ('DEVELOPMENT_SIMULATED','SIMULATED','TEST') THEN 1 ELSE 0 END excludedEnvironment
        FROM nx_payment_record p LEFT JOIN nx_wallet_ledger l ON l.id=p.wallet_ledger_id
        LEFT JOIN nx_user u ON u.id=p.user_id
        WHERE p.is_deleted=0 AND p.user_id IN
        """ + USERS + """
        AND (p.wallet_ledger_id IS NOT NULL OR p.payment_status IN ('CONFIRMED','CREDITED','SUCCESS')) AND
        """ + SupportFinanceSql.CARD_SCOPE + "</script>";
    static final String VIETQR = """
        <script>SELECT 'DEPOSIT' kind,'VIETQR' source,CONCAT('nx_vietqr_reconciliation:',r.id) sourceId,
        r.user_id customerId,CONCAT('D1-VIETQR-',r.reconciliation_no) businessId,
        COALESCE(r.intent_no,CONCAT('D1-VIETQR-',r.reconciliation_no)) duplicateKey,r.credited_usdt amount,'USDT' currency,
        l.created_at succeededAt,'nx_wallet_ledger.created_at' successTimeField,0 fractionalSecondDigits,
        r.received_at providerPaidAt,CAST(r.version AS CHAR) sourceVersion,1 sourceLinked,
        """ + LEDGER + """
        FROM nx_vietqr_reconciliation r LEFT JOIN nx_wallet_ledger l
          ON l.biz_no=CONCAT('D1-VIETQR-',r.reconciliation_no) AND l.direction='IN' AND l.asset='USDT'
        LEFT JOIN nx_user u ON u.id=r.user_id
        WHERE r.is_deleted=0 AND r.user_id IN
        """ + USERS + """
        AND (l.id IS NOT NULL OR r.status='CREDITED')
        AND NOT (l.id IS NULL AND EXISTS(SELECT 1 FROM nx_hdpay_payin_order h
          JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id
          JOIN nx_wallet_ledger hl ON hl.biz_no=i.intent_no AND hl.user_id=i.user_id
          WHERE i.intent_no=r.intent_no AND i.user_id=r.user_id AND i.is_deleted=0
          AND i.payment_rail='HDPAY' AND i.settlement_target_type='WALLET_TOPUP'
          AND h.wallet_ledger_biz_no=hl.biz_no AND h.settled_at IS NOT NULL
          AND hl.biz_type='VIETQR_DEPOSIT' AND hl.asset='USDT' AND hl.direction='IN' AND hl.status='SUCCESS' AND hl.is_deleted=0
          AND hl.amount=r.credited_usdt AND hl.amount=h.settled_usdt AND hl.amount=i.credited_usdt
          AND hl.amount=i.requested_usdt AND i.payable_vnd=h.amount_vnd AND i.received_vnd=h.amount_vnd))</script>
        """;
    static final String HDPAY = """
        <script>SELECT 'DEPOSIT' kind,'HDPAY' source,CONCAT('nx_hdpay_payin_order:',h.id) sourceId,
        i.user_id customerId,i.intent_no businessId,i.intent_no duplicateKey,h.settled_usdt amount,'USDT' currency,
        h.settled_at succeededAt,'nx_hdpay_payin_order.settled_at' successTimeField,0 fractionalSecondDigits,
        NULL providerPaidAt,CAST(h.version AS CHAR) sourceVersion,
        CASE WHEN i.is_deleted=0 AND i.payment_rail='HDPAY' AND i.settlement_target_type='WALLET_TOPUP'
          AND h.wallet_ledger_biz_no=i.intent_no AND i.credited_usdt=h.settled_usdt
          AND i.requested_usdt=h.settled_usdt AND i.payable_vnd=h.amount_vnd AND i.received_vnd=h.amount_vnd
          THEN 1 ELSE 0 END sourceLinked,
        """ + LEDGER + """
        FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id
        LEFT JOIN nx_wallet_ledger l ON l.biz_no=i.intent_no AND l.asset='USDT' AND l.direction='IN'
        LEFT JOIN nx_user u ON u.id=i.user_id
        WHERE i.user_id IN
        """ + USERS + """
        AND i.payment_rail='HDPAY' AND i.settlement_target_type='WALLET_TOPUP'
        AND (h.settled_at IS NOT NULL OR h.settlement_status='CREDITED' OR i.status='CREDITED')</script>
        """;
    static final String ORDERS = """
        <script>SELECT 'DEVICE_PURCHASE' kind,
        CASE o.order_type WHEN 'TRADE_IN' THEN 'TRADE_IN' WHEN 'CAPACITY_KEEP' THEN 'CAPACITY_KEEP' ELSE 'WALLET_ORDER' END source,
        CONCAT('nx_order:',o.id) sourceId,o.user_id customerId,o.order_no businessId,o.order_no duplicateKey,
        o.order_no orderNo,o.order_type orderType,o.amount_usdt amount,'USDT' currency,
        o.paid_at succeededAt,'nx_order.paid_at' successTimeField,6 fractionalSecondDigits,
        NULL providerPaidAt,NULL sourceVersion,
        CASE WHEN o.order_type IN ('TRADE_IN','CAPACITY_KEEP') THEN 1
          WHEN (SELECT COUNT(*) FROM nx_payment_record p WHERE p.payment_no=o.payment_no
            AND p.order_no=o.order_no AND p.user_id=o.user_id AND p.amount_usdt=o.amount_usdt
            AND p.currency='USDT' AND p.provider='NEXGRID_WALLET' AND p.paid_at IS NOT NULL AND p.is_deleted=0
            AND p.payment_status IN ('PAID','CONFIRMED','SUCCESS','REFUNDED')
            AND (p.wallet_ledger_id IS NULL OR p.wallet_ledger_id=l.id))=1
          THEN 1 ELSE 0 END sourceLinked,
        (SELECT MIN(p.paid_at) FROM nx_payment_record p WHERE p.payment_no=o.payment_no
          AND p.order_no=o.order_no AND p.user_id=o.user_id AND p.amount_usdt=o.amount_usdt
          AND p.currency='USDT' AND p.provider='NEXGRID_WALLET' AND p.is_deleted=0
          AND p.payment_status IN ('PAID','CONFIRMED','SUCCESS','REFUNDED')
          AND (p.wallet_ledger_id IS NULL OR p.wallet_ledger_id=l.id)) sourceConfirmationAt,
        """ + LEDGER + """
        FROM nx_order o LEFT JOIN nx_wallet_ledger l ON l.biz_no=o.order_no AND l.direction='OUT' AND l.asset='USDT'
          AND l.biz_type=CASE o.order_type WHEN 'TRADE_IN' THEN 'TRADE_IN_PURCHASE'
            WHEN 'CAPACITY_KEEP' THEN 'DEVICE_PURCHASE' ELSE 'ORDER_PURCHASE' END
        LEFT JOIN nx_user u ON u.id=o.user_id
        WHERE o.is_deleted=0 AND o.user_id IN
        """ + USERS + """
        AND o.order_type IN ('SINGLE','BUNDLE','TRADE_IN','CAPACITY_KEEP')
        AND (o.paid_at IS NOT NULL OR o.payment_status IN ('PAID','REFUNDED','SUCCESS','CONFIRMED'))</script>
        """;
    static final String TRIALS = """
        <script>SELECT 'DEVICE_PURCHASE' kind,'TRIAL_CONVERT' source,CONCAT('nx_order:',o.id) sourceId,
        o.user_id customerId,CONCAT(c.claim_no,':CHARGE') businessId,CONCAT(c.claim_no,':CHARGE') duplicateKey,
        o.order_no orderNo,o.order_type orderType,o.amount_usdt amount,'USDT' currency,
        o.paid_at succeededAt,'nx_order.paid_at' successTimeField,6 fractionalSecondDigits,
        c.settled_at sourceConfirmationAt,NULL providerPaidAt,CAST(c.version AS CHAR) sourceVersion,
        CASE WHEN c.user_id=o.user_id AND c.status='REDEEMED' AND c.settled_at IS NOT NULL
          AND c.settlement_amount_usdt=o.amount_usdt THEN 1 ELSE 0 END sourceLinked,
        l.id ledgerId,l.user_id ledgerCustomerId,l.biz_no ledgerBusinessId,l.biz_type ledgerType,
        l.asset ledgerCurrency,l.amount ledgerAmount,l.direction ledgerDirection,l.status ledgerStatus,l.is_deleted ledgerDeleted,
        l.created_at ledgerRecordedAt,
        CASE WHEN u.sandbox=1 OR d.source_environment IN ('SANDBOX','TEST','SIMULATED','DEVELOPMENT') THEN 1 ELSE 0 END excludedEnvironment
        FROM nx_order o LEFT JOIN nx_user_device d ON d.source_order_no=o.order_no AND d.user_id=o.user_id
        LEFT JOIN nx_trial_claim c ON c.user_device_id=d.id
        LEFT JOIN nx_wallet_ledger l ON l.biz_no=CONCAT(c.claim_no,':CHARGE') AND l.direction='OUT' AND l.asset='USDT'
          AND l.biz_type='TRIAL_CHARGE'
        LEFT JOIN nx_user u ON u.id=o.user_id
        WHERE o.is_deleted=0 AND o.order_type='TRIAL_CONVERT' AND o.user_id IN
        """ + USERS + """
        AND (o.paid_at IS NOT NULL OR o.payment_status IN ('PAID','REFUNDED','SUCCESS','CONFIRMED'))</script>
        """;
    static final String REFUNDS = """
        <script>SELECT 'DEVICE_PURCHASE_REFUND' kind,'ORDER_REFUND' source,CONCAT('nx_wallet_ledger:',l.id) sourceId,
        l.user_id customerId,l.biz_no businessId,l.biz_no duplicateKey,o.order_no orderNo,o.order_type orderType,
        l.amount amount,l.asset currency,l.created_at succeededAt,'nx_wallet_ledger.created_at' successTimeField,
        0 fractionalSecondDigits,NULL providerPaidAt,NULL sourceVersion,
        CASE WHEN o.user_id=l.user_id THEN 1 ELSE 0 END sourceLinked,
        """ + LEDGER + """
        FROM nx_wallet_ledger l LEFT JOIN nx_order o ON l.biz_no=CONCAT('E4-REFUND-',o.order_no)
        LEFT JOIN nx_user u ON u.id=l.user_id
        WHERE l.is_deleted=0 AND l.biz_type='ORDER_REFUND' AND l.user_id IN
        """ + USERS + """
        AND l.status='SUCCESS'</script>
        """;
    static final String FREE_TRIALS = """
        <script>SELECT 'DEVICE_PURCHASE' kind,'FREE_TRIAL' source,CONCAT('nx_trial_claim:',c.id) sourceId,
        c.user_id customerId,0 amount,1 excludedEnvironment FROM nx_trial_claim c
        WHERE c.is_deleted=0 AND c.status NOT IN ('REDEEMED') AND c.user_id IN
        """ + USERS + "</script>";
    static final String UNMATCHED = """
        <script>SELECT CASE WHEN l.direction='IN' THEN 'DEPOSIT' ELSE 'DEVICE_PURCHASE' END kind,
        'UNMATCHED_LEDGER' source,CONCAT('nx_wallet_ledger:',l.id) sourceId,l.id ledgerId,
        l.user_id customerId,l.biz_no businessId,l.amount amount,l.asset currency
        FROM nx_wallet_ledger l WHERE l.is_deleted=0 AND l.user_id IN
        """ + USERS + """
        AND ((l.direction='IN' AND l.status='SUCCESS'
          AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP','CARD_TOPUP','VIETQR_DEPOSIT') AND NOT
        """ + SupportFinanceSql.CREDIT_MATCH + """
        ) OR (l.direction='OUT' AND l.status='POSTED' AND l.biz_type='TRIAL_CHARGE'
          AND NOT EXISTS(SELECT 1 FROM nx_trial_claim c JOIN nx_user_device d ON d.id=c.user_device_id
            JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=c.user_id AND o.order_type='TRIAL_CONVERT'
            WHERE c.user_id=l.user_id AND CONCAT(c.claim_no,':CHARGE')=l.biz_no))
          OR (l.direction='OUT' AND l.status='SUCCESS'
            AND l.biz_type IN ('ORDER_PURCHASE','TRADE_IN_PURCHASE','DEVICE_PURCHASE')
            AND NOT EXISTS(SELECT 1 FROM nx_order o WHERE o.order_no=l.biz_no AND o.user_id=l.user_id
              AND o.order_type IN ('SINGLE','BUNDLE','TRADE_IN','CAPACITY_KEEP'))))</script>
        """;
}
