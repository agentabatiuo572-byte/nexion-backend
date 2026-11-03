package ffdd.opsconsole.shared.canonical.mapper;

/** Original amount paid, including fees on the delivered external intent for this exact order. */
public final class OrderPaidTotalReadSql {
    private OrderPaidTotalReadSql() {}

    public static final String PAID_TOTAL = """
            CASE WHEN o.payment_status IN ('PAID','REFUNDED') THEN COALESCE(
              (SELECT JSON_UNQUOTE(JSON_EXTRACT(i.quote_json,'$.totalUsdt'))
                 FROM nx_purchase_payment_intent i
                WHERE i.intent_no=o.payment_no AND i.user_id=o.user_id AND i.receipt_json IS NOT NULL
                  AND JSON_UNQUOTE(JSON_EXTRACT(i.receipt_json,'$.orderNo'))=o.order_no),
              CASE WHEN (o.payment_no IS NULL AND o.order_type IN ('TRADE_IN','CAPACITY_KEEP','TRIAL_CONVERT'))
                     OR EXISTS (SELECT 1 FROM nx_payment_record pr
                                 WHERE pr.order_no=o.order_no AND pr.user_id=o.user_id AND pr.is_deleted=0
                                   AND (pr.payment_no=o.payment_no OR o.payment_no IS NULL)
                                   AND pr.payment_status IN ('PAID','REFUNDED')
                                   AND pr.provider IN ('UVEL_WALLET','USDT_WALLET','UVEL_USDT_WALLET','VOUCHER'))
                   THEN CAST(CAST(o.amount_usdt AS DECIMAL(18,2)) AS CHAR) END)
            END
            """;
}
