package ffdd.opsconsole.finance.mapper;

/** Read-only D1 projection. Never insert a bank receipt or invoke settlement while displaying history. */
final class D1HdPayReadSql {
    private D1HdPayReadSql() {}

    static final String CTES = """
            hdpay_wallet_orders AS (
                SELECT h.id, h.merchant_order_id AS intent_no, h.provider_order_id,
                       h.provider_status, h.settlement_status, h.amount_vnd, h.settled_usdt,
                       h.wallet_ledger_biz_no, h.settled_at, h.created_at, h.updated_at,
                       i.user_id, i.requested_usdt, i.credited_usdt, i.status AS intent_status,
                       i.payable_vnd, i.received_vnd, i.locked_fx_rate_vnd_per_usdt,
                       i.expires_at, i.version,
                       (SELECT m.confirmation_no FROM nx_hdpay_manual_confirmation m
                         WHERE m.merchant_order_id=i.intent_no AND m.confirmation_source='ADMIN_MANUAL'
                           AND m.user_id=i.user_id AND m.received_vnd=i.received_vnd
                           AND m.credited_usdt=i.credited_usdt AND m.credited_usdt=h.settled_usdt
                           AND ((m.reserve_source='RESERVE_LEDGER' AND EXISTS (
                             SELECT 1 FROM nx_treasury_reserve_ledger t
                              WHERE t.voucher_no=i.intent_no AND t.direction='IN' AND t.status='CONFIRMED'
                                AND t.amount_usd=i.credited_usdt AND t.is_deleted=0))
                             OR (m.reserve_source='EXISTING_BANK_RECEIPT' AND EXISTS (
                               SELECT 1 FROM nx_vietqr_reconciliation r
                                WHERE r.id=m.bank_receipt_id AND r.reconciliation_no=m.bank_reconciliation_no
                                  AND r.intent_no=i.intent_no AND r.user_id=i.user_id AND r.status='CREDITED'
                                  AND r.credited_usdt=i.credited_usdt AND r.received_vnd=m.received_vnd
                                  AND r.payment_reference=m.payment_reference AND r.received_at=m.received_at
                                  AND r.locked_fx_rate_vnd_per_usdt > 0
                                  AND r.received_vnd=i.credited_usdt*r.locked_fx_rate_vnd_per_usdt
                                  AND r.is_deleted=0)))
                       ) AS manual_confirmation_no
                  FROM nx_hdpay_payin_order h
                  JOIN nx_vietqr_intent i ON i.intent_no = h.merchant_order_id
                 WHERE i.is_deleted = 0 AND i.payment_rail = 'HDPAY'
                   AND i.settlement_target_type = 'WALLET_TOPUP'
                   AND i.payable_vnd = h.amount_vnd AND i.requested_usdt > 0
            ), hdpay_credited AS (
                SELECT h.*, l.created_at AS ledger_created_at
                  FROM hdpay_wallet_orders h
                  JOIN nx_wallet_ledger l
                    ON l.biz_no = h.intent_no AND l.biz_no = h.wallet_ledger_biz_no
                   AND l.user_id = h.user_id AND l.biz_type = 'VIETQR_DEPOSIT'
                   AND l.asset = 'USDT' AND l.direction = 'IN' AND l.status = 'SUCCESS'
                   AND l.is_deleted = 0 AND l.amount = h.settled_usdt
                   AND l.amount = h.credited_usdt AND l.amount = h.requested_usdt
                 WHERE h.settlement_status = 'CREDITED' AND h.intent_status = 'CREDITED'
                   AND h.settled_at IS NOT NULL
                   AND h.received_vnd = h.amount_vnd
                   AND (h.manual_confirmation_no IS NOT NULL OR (h.provider_status = 3 AND NOT EXISTS (
                     SELECT 1 FROM nx_hdpay_manual_confirmation m WHERE m.merchant_order_id=h.intent_no)))
            )
            """;

    static final String MATCHED_ROWS = """
            SELECT -h.id AS id, CONCAT('HDPAY-', h.intent_no) AS reconciliationNo,
                   h.intent_no AS intentNo, h.user_id AS userId,
                   NULL AS bankAccountId, 'HDPAY' AS paymentRail,
                   NULL AS assignedBankAccountId, NULL AS memoCode,
                   NULL AS mismatchReason, 'MATCHED' AS viewType, 'CREDITED' AS status,
                   h.payable_vnd AS payableVnd, h.received_vnd AS receivedVnd,
                   h.locked_fx_rate_vnd_per_usdt AS lockedFxRateVndPerUsdt,
                   h.settled_usdt AS creditedUsdt, h.provider_order_id AS paymentReference,
                   CASE WHEN h.manual_confirmation_no IS NOT NULL
                     THEN 'HDPay 已人工核实入账；只读记录，无需再次入账'
                     ELSE 'HDPay 已确认并自动入账；只读记录，无需人工入账' END AS note,
                   h.expires_at AS expiresAt, h.settled_at AS receivedAt,
                   FALSE AS intentTransitionRequired, h.version,
                   h.created_at AS createdAt, h.updated_at AS updatedAt
              FROM hdpay_credited h
             WHERE NOT EXISTS (
                 SELECT 1 FROM nx_vietqr_reconciliation existing
                  WHERE existing.intent_no = h.intent_no AND existing.is_deleted = 0
                    AND existing.view_type = 'MATCHED' AND existing.status = 'CREDITED'
                    AND existing.user_id = h.user_id AND existing.credited_usdt = h.settled_usdt
             )
            """;
}
