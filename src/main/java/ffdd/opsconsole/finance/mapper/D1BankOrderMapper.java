package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface D1BankOrderMapper extends BaseMapper<Object> {
    // One canonical order, regardless of APP projection closure or receipt count.
    String ORDERS = """
            WITH bank_orders AS (
              SELECT i.intent_no AS intentNo, i.payment_rail AS paymentRail, i.user_id AS userId,
                     i.settlement_target_type AS settlementTargetType, i.target_order_no AS targetOrderNo,
                     i.status AS intentStatus,
                     CASE
                       WHEN i.status <> 'AWAITING_PAYMENT' THEN i.status
                       WHEN i.expires_at <= #{asOf} THEN 'EXPIRED'
                       WHEN i.payment_rail = 'MANUAL' THEN 'AWAITING_PAYMENT'
                       WHEN h.settlement_status IN ('MANUAL_REVIEW','CREDITED') THEN 'PROCESSING'
                       WHEN h.submission_status = 'SUBMIT_UNKNOWN' THEN 'UNKNOWN'
                       WHEN h.submission_status = 'REJECTED' THEN 'FAILED'
                       WHEN h.submission_status = 'CREATED' THEN 'AWAITING_PAYMENT'
                       ELSE 'CREATING'
                     END AS status,
                     i.requested_usdt AS requestedUsdt, i.payable_vnd AS payableVnd,
                     i.received_vnd AS receivedVnd, i.credited_usdt AS creditedUsdt,
                     i.locked_fx_rate_vnd_per_usdt AS lockedFxRateVndPerUsdt,
                     i.bank_account_id AS bankAccountId, i.memo_code AS memoCode,
                     i.expires_at AS expiresAt,
                     COALESCE(m.received_at, i.matched_at) AS receivedAt,
                     i.created_at AS createdAt, i.updated_at AS updatedAt, i.version,
                     h.version AS providerVersion, h.submission_status AS submissionStatus,
                     h.provider_status AS providerStatus, h.settlement_status AS settlementStatus,
                     h.amount_vnd AS providerAmountVnd, h.payment_url AS paymentUrl,
                     m.confirmation_no AS manualConfirmationNo,
                     m.reserve_source AS reserveSource, m.bank_receipt_id AS bankReceiptId,
                     CASE
                       WHEN i.status <> 'CREDITED' THEN 'UNCONFIRMED'
                       WHEN m.merchant_order_id IS NOT NULL
                            AND m.confirmation_source='ADMIN_MANUAL'
                            AND m.user_id=i.user_id AND m.credited_usdt=i.credited_usdt
                            AND i.credited_usdt=i.requested_usdt
                            AND m.received_vnd=i.received_vnd AND i.received_vnd=h.amount_vnd
                            AND h.settlement_status='CREDITED' AND h.settled_usdt=i.credited_usdt
                            AND h.wallet_ledger_biz_no=i.intent_no
                            AND EXISTS (SELECT 1 FROM nx_wallet_ledger l
                              WHERE l.biz_no=i.intent_no AND l.user_id=i.user_id
                                AND l.biz_type='VIETQR_DEPOSIT' AND l.asset='USDT'
                                AND l.direction='IN' AND l.status='SUCCESS'
                                AND l.amount=i.credited_usdt AND l.is_deleted=0)
                            AND ((m.reserve_source='RESERVE_LEDGER' AND EXISTS (SELECT 1 FROM nx_treasury_reserve_ledger t
                              WHERE t.voucher_no=i.intent_no AND t.direction='IN'
                                AND t.status='CONFIRMED' AND t.amount_usd=i.credited_usdt AND t.is_deleted=0))
                              OR (m.reserve_source='EXISTING_BANK_RECEIPT' AND EXISTS (
                                SELECT 1 FROM nx_vietqr_reconciliation r
                                 WHERE r.id=m.bank_receipt_id AND r.reconciliation_no=m.bank_reconciliation_no
                                   AND r.intent_no=i.intent_no AND r.user_id=i.user_id
                                   AND r.status='CREDITED' AND r.credited_usdt=i.credited_usdt
                                   AND r.received_vnd=m.received_vnd AND r.payment_reference=m.payment_reference
                                   AND r.received_at=m.received_at AND r.locked_fx_rate_vnd_per_usdt > 0
                                   AND r.received_vnd=i.credited_usdt*r.locked_fx_rate_vnd_per_usdt
                                   AND r.is_deleted=0)))
                            THEN 'ADMIN_MANUAL'
                       WHEN i.payment_rail='MANUAL' AND EXISTS (
                            SELECT 1 FROM nx_vietqr_reconciliation r
                             WHERE r.intent_no=i.intent_no AND r.user_id=i.user_id
                               AND r.status='CREDITED' AND r.credited_usdt=i.credited_usdt
                               AND r.received_vnd > 0 AND r.is_deleted=0)
                            THEN 'BANK_RECEIPT'
                       WHEN i.payment_rail='HDPAY' AND h.provider_status=3
                            AND m.merchant_order_id IS NULL
                            AND h.settlement_status='CREDITED' AND h.settled_usdt=i.credited_usdt
                            AND h.wallet_ledger_biz_no=i.intent_no AND h.settled_at IS NOT NULL
                            AND i.credited_usdt=i.requested_usdt AND i.credited_usdt > 0
                            AND i.received_vnd=h.amount_vnd AND i.payable_vnd=h.amount_vnd
                            AND EXISTS (SELECT 1 FROM nx_wallet_ledger l
                              WHERE l.biz_no=i.intent_no AND l.user_id=i.user_id
                                AND l.biz_type='VIETQR_DEPOSIT' AND l.asset='USDT'
                                AND l.direction='IN' AND l.status='SUCCESS'
                                AND l.amount=i.credited_usdt AND l.is_deleted=0)
                            THEN 'AUTO_PROVIDER'
                       ELSE 'UNKNOWN'
                     END AS confirmationSource,
                     (SELECT COUNT(*) FROM nx_vietqr_reconciliation r
                       WHERE r.intent_no=i.intent_no AND r.status='OPEN'
                         AND r.received_vnd > 0 AND r.is_deleted=0) AS openReceiptCount,
                     (SELECT COUNT(*) FROM nx_vietqr_reconciliation r
                       WHERE r.intent_no=i.intent_no AND r.received_vnd > 0 AND r.is_deleted=0) AS bankReceiptCount,
                     (SELECT COUNT(*) FROM nx_vietqr_reconciliation r
                       WHERE r.intent_no=i.intent_no AND r.user_id=i.user_id AND r.status='OPEN'
                         AND r.view_type IN ('MATCHED','MISMATCH','LATE','ORPHAN')
                         AND r.bank_account_id IS NOT NULL AND r.payable_vnd=i.payable_vnd
                         AND r.received_vnd=i.payable_vnd AND r.locked_fx_rate_vnd_per_usdt > 0
                         AND r.received_vnd=i.requested_usdt*r.locked_fx_rate_vnd_per_usdt
                         AND COALESCE(r.credited_usdt,0)=0 AND r.payment_reference IS NOT NULL
                         AND r.payment_reference<>'' AND r.received_at>=i.created_at AND r.is_deleted=0)
                       AS reusableReceiptCount,
                     ((SELECT COUNT(*) FROM nx_wallet_ledger l WHERE l.biz_no=i.intent_no
                         OR EXISTS (SELECT 1 FROM nx_vietqr_reconciliation r
                           WHERE r.intent_no=i.intent_no AND l.biz_no=CONCAT('D1-VIETQR-',r.reconciliation_no)))
                      + (SELECT COUNT(*) FROM nx_treasury_reserve_ledger t WHERE t.voucher_no=i.intent_no)
                      + (SELECT COUNT(*) FROM nx_hdpay_manual_confirmation m2 WHERE m2.merchant_order_id=i.intent_no)
                      + (SELECT COUNT(*) FROM nx_vietqr_reconciliation r2
                          WHERE r2.intent_no=i.intent_no
                            AND (r2.credited_usdt > 0 OR r2.status IN ('RETURN_PENDING','RETURNED'))))
                      AS settlementFactCount,
                     EXISTS (SELECT 1 FROM nx_vietqr_bank_account b
                       WHERE b.id=i.bank_account_id AND b.status='ACTIVE' AND b.is_deleted=0) AS bankAccountActive
                FROM nx_vietqr_intent i
                LEFT JOIN nx_hdpay_payin_order h
                  ON i.payment_rail='HDPAY' AND h.merchant_order_id=i.intent_no
                LEFT JOIN nx_hdpay_manual_confirmation m
                  ON i.payment_rail='HDPAY' AND m.merchant_order_id=i.intent_no
               WHERE i.is_deleted=0 AND i.payment_rail IN ('MANUAL','HDPAY')
                 AND i.settlement_target_type='WALLET_TOPUP'
            )
            """;
    String FILTER = """
             FROM bank_orders
            WHERE (#{paymentRail} IS NULL OR paymentRail=#{paymentRail})
              AND (#{status} IS NULL OR status=#{status})
              AND (#{keyword} IS NULL OR intentNo LIKE CONCAT('%',#{keyword},'%')
                   OR memoCode LIKE CONCAT('%',#{keyword},'%') OR CAST(userId AS CHAR)=#{keyword})
            """;

    @Select(ORDERS + " SELECT COUNT(*) " + FILTER)
    long countOrders(@Param("paymentRail") String paymentRail, @Param("status") String status,
                     @Param("keyword") String keyword, @Param("asOf") LocalDateTime asOf);

    @Select(ORDERS + " SELECT * " + FILTER + " ORDER BY createdAt DESC, intentNo DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> listOrders(@Param("paymentRail") String paymentRail, @Param("status") String status,
            @Param("keyword") String keyword, @Param("asOf") LocalDateTime asOf,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT (SELECT COUNT(*) FROM nx_wallet_ledger l WHERE l.biz_no=#{intentNo}
                      OR EXISTS (SELECT 1 FROM nx_vietqr_reconciliation r
                        WHERE r.intent_no=#{intentNo} AND l.biz_no=CONCAT('D1-VIETQR-',r.reconciliation_no)))
                 + (SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE voucher_no=#{intentNo})
                 + (SELECT COUNT(*) FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=#{intentNo})
                 + (SELECT COUNT(*) FROM nx_vietqr_reconciliation
                     WHERE intent_no=#{intentNo}
                       AND (credited_usdt > 0 OR status IN ('RETURN_PENDING','RETURNED')))
            """)
    long countSettlementFacts(@Param("intentNo") String intentNo);

    // Lock receipts before the intent, matching the existing bank-reconciliation lock order.
    @Select("""
            SELECT id,reconciliation_no AS reconciliationNo,intent_no AS intentNo,user_id AS userId,
                   bank_account_id AS bankAccountId,view_type AS viewType,status,payable_vnd AS payableVnd,
                   received_vnd AS receivedVnd,locked_fx_rate_vnd_per_usdt AS lockedFxRateVndPerUsdt,
                   credited_usdt AS creditedUsdt,payment_reference AS paymentReference,
                   received_at AS receivedAt,version,note
              FROM nx_vietqr_reconciliation
             WHERE intent_no=#{intentNo} AND received_vnd > 0 AND is_deleted=0
             ORDER BY id FOR UPDATE
            """)
    List<Map<String,Object>> lockBankReceipts(@Param("intentNo") String intentNo);

    @Select("""
            SELECT id FROM nx_vietqr_reconciliation
             WHERE payment_reference=#{reference}
             FOR UPDATE
            """)
    List<Long> lockBankReference(@Param("reference") String reference);

    @Insert("""
            INSERT INTO nx_hdpay_manual_confirmation (
              confirmation_no,merchant_order_id,user_id,received_vnd,credited_usdt,
              payment_reference,received_at,evidence_ref,reason,operator,idempotency_key,
              reserve_source,bank_receipt_id,bank_receipt_version,bank_reconciliation_no,previous_intent_status)
            VALUES (#{confirmationNo},#{intentNo},#{userId},#{receivedVnd},#{creditedUsdt},
                    #{reference},#{receivedAt},#{evidenceRef},#{reason},#{operator},#{key},
                    #{reserveSource},#{receiptId},#{receiptVersion},#{reconciliationNo},#{previousIntentStatus})
            """)
    int insertManualConfirmation(@Param("confirmationNo") String confirmationNo,
            @Param("intentNo") String intentNo, @Param("userId") Long userId,
            @Param("receivedVnd") BigDecimal receivedVnd, @Param("creditedUsdt") BigDecimal creditedUsdt,
            @Param("reference") String reference, @Param("receivedAt") LocalDateTime receivedAt,
            @Param("evidenceRef") String evidenceRef, @Param("reason") String reason,
            @Param("operator") String operator, @Param("key") String key,
            @Param("reserveSource") String reserveSource, @Param("receiptId") Long receiptId,
            @Param("receiptVersion") Long receiptVersion, @Param("reconciliationNo") String reconciliationNo,
            @Param("previousIntentStatus") String previousIntentStatus);

    // Only a local settlement fact. Provider identity, status and submission are untouched.
    @Update("""
            UPDATE nx_hdpay_payin_order
               SET settlement_status='CREDITED', settled_usdt=#{amount}, wallet_ledger_biz_no=#{intentNo},
                   settled_at=#{now}, last_error_code='HDPAY_ADMIN_MANUAL_CREDITED',
                   version=version+1, updated_at=#{now}
             WHERE merchant_order_id=#{intentNo} AND version=#{version}
               AND settlement_status IN ('UNSETTLED','MANUAL_REVIEW')
            """)
    int markManualCredited(@Param("intentNo") String intentNo, @Param("version") Long version,
                          @Param("amount") BigDecimal amount, @Param("now") LocalDateTime now);
}
