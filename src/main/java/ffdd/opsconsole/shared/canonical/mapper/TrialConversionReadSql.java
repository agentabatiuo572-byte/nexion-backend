package ffdd.opsconsole.shared.canonical.mapper;

/** Read-only compatibility for fulfilled trial orders; persisted states and financial facts stay intact. */
public final class TrialConversionReadSql {
    private TrialConversionReadSql() {}

    private static final String OWNED_REDEEMED_DEVICE = """
            UPPER(o.order_type)='TRIAL_CONVERT' AND o.quantity=1 AND o.item_count=1
            AND o.is_deleted=0 AND o.paid_at IS NOT NULL
            AND tc.user_id=o.user_id AND tc.is_deleted=0 AND UPPER(tc.status)='REDEEMED'
            AND tc.settled_at IS NOT NULL AND tc.settlement_amount_usdt=o.amount_usdt
            AND td.user_id=o.user_id AND td.source_order_no=o.order_no AND td.product_id=o.product_id
            AND td.is_deleted=0 AND td.source_channel='TRIAL'
            AND td.source_environment='PRODUCTION' AND td.run_id='' AND td.activated_at IS NOT NULL
            """;

    // The legacy writer used NOW(6) for paid_at and NOW() for activated_at. Exact fulfillment
    // proof, rather than ordering these different precisions, authorizes this projection.
    public static final String LEGACY_FULFILLED = """
            (UPPER(o.payment_status)='PAID' AND UPPER(o.order_status)='PAID' AND UPPER(o.activation_status)='ACTIVE' AND EXISTS (
              SELECT 1 FROM nx_trial_claim tc JOIN nx_user_device td ON td.id=tc.user_device_id
               WHERE
            """ + OWNED_REDEEMED_DEVICE + "))";

    // Original posted charge remains traceable after a later refund or chargeback.
    public static final String TRIAL_CHARGE = """
            EXISTS (SELECT 1 FROM nx_order o
              JOIN nx_trial_claim tc ON tc.user_id=o.user_id
              JOIN nx_user_device td ON td.id=tc.user_device_id
             WHERE o.order_no=#{orderNo} AND
            """ + OWNED_REDEEMED_DEVICE + """
               AND wl.user_id=o.user_id AND wl.biz_no=CONCAT(tc.claim_no,':CHARGE')
               AND wl.biz_type='TRIAL_CHARGE' AND wl.asset='USDT' AND wl.direction='OUT'
               AND wl.status='POSTED' AND wl.amount=o.amount_usdt AND wl.amount>0)
            """;
}
