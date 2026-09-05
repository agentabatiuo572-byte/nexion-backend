package ffdd.opsconsole.finance.hdpay;

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
public interface HdPayOrderMapper extends BaseMapper<Object> {
    @Select("""
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema=DATABASE()
               AND table_name IN (
                 'nx_hdpay_payin_order',
                 'nx_hdpay_callback_inbox',
                 'nx_hdpay_settlement_review')
            """)
    int countRequiredSchemaTables();

    @Select("""
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema=DATABASE()
               AND CONCAT(table_name, '.', column_name) IN (
                 'nx_hdpay_payin_order.settlement_status',
                 'nx_hdpay_payin_order.settled_usdt',
                 'nx_hdpay_payin_order.wallet_ledger_biz_no',
                 'nx_hdpay_payin_order.settled_at',
                 'nx_hdpay_callback_inbox.processing_status',
                 'nx_hdpay_callback_inbox.claim_token',
                 'nx_hdpay_callback_inbox.claimed_at',
                 'nx_hdpay_callback_inbox.provider_query_status',
                 'nx_hdpay_callback_inbox.result_code',
                 'nx_hdpay_callback_inbox.processed_at',
                 'nx_hdpay_settlement_review.callback_payload_hash',
                 'nx_hdpay_settlement_review.status',
                 'nx_vietqr_intent.settlement_target_type',
                 'nx_vietqr_intent.target_order_no')
            """)
    int countRequiredSchemaColumns();

    @Select("""
            SELECT COUNT(DISTINCT CONCAT(table_name, '.', index_name))
              FROM information_schema.statistics
             WHERE table_schema=DATABASE() AND non_unique=0
               AND CONCAT(table_name, '.', index_name) IN (
                 'nx_hdpay_payin_order.uk_nx_hdpay_payin_merchant_order',
                 'nx_hdpay_payin_order.uk_nx_hdpay_payin_provider_order',
                 'nx_hdpay_callback_inbox.uk_nx_hdpay_callback_payload',
                 'nx_hdpay_settlement_review.uk_nx_hdpay_review_no',
                 'nx_hdpay_settlement_review.uk_nx_hdpay_review_callback',
                 'nx_vietqr_intent.uk_vietqr_intent_target_order')
            """)
    int countRequiredUniqueIndexes();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.check_constraints
             WHERE constraint_schema=DATABASE()
               AND constraint_name='chk_vietqr_intent_settlement_target'
            """)
    int countSettlementTargetCheck();

    @Select("""
            SELECT COUNT(*)
              FROM (
                    SELECT index_name,
                           GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS columns_csv
                      FROM information_schema.statistics
                     WHERE table_schema=DATABASE()
                       AND table_name='nx_hdpay_callback_inbox'
                       AND index_name='idx_nx_hdpay_callback_recovery'
                     GROUP BY index_name
                    HAVING columns_csv='processing_status,updated_at'
                   ) expected_index
            """)
    int countCallbackRecoveryIndex();

    @Select("""
            SELECT merchant_order_id AS merchantOrderId,
                   amount_vnd AS amountVnd,
                   submission_status AS submissionStatus,
                   payment_url AS paymentUrl,
                   provider_order_id AS providerOrderId,
                   provider_status AS providerStatus,
                   settlement_status AS settlementStatus,
                   settled_usdt AS settledUsdt,
                   wallet_ledger_biz_no AS walletLedgerBizNo,
                   settled_at AS settledAt,
                   last_error_code AS lastErrorCode,
                   version, created_at AS createdAt, updated_at AS updatedAt
              FROM nx_hdpay_payin_order
             WHERE merchant_order_id = #{merchantOrderId}
             LIMIT 1
            """)
    Map<String, Object> findByMerchantOrderId(@Param("merchantOrderId") String merchantOrderId);

    @Select("""
            SELECT merchant_order_id AS merchantOrderId,
                   amount_vnd AS amountVnd,
                   submission_status AS submissionStatus,
                   payment_url AS paymentUrl,
                   provider_order_id AS providerOrderId,
                   provider_status AS providerStatus,
                   settlement_status AS settlementStatus,
                   settled_usdt AS settledUsdt,
                   wallet_ledger_biz_no AS walletLedgerBizNo,
                   settled_at AS settledAt,
                   last_error_code AS lastErrorCode,
                   version, created_at AS createdAt, updated_at AS updatedAt
              FROM nx_hdpay_payin_order
             WHERE merchant_order_id = #{merchantOrderId}
             LIMIT 1
             FOR UPDATE
            """)
    Map<String, Object> findByMerchantOrderIdForUpdate(
            @Param("merchantOrderId") String merchantOrderId);

    @Insert("""
            -- INSERT IGNORE is the single-writer claim: duplicates must report zero,
            -- never the JDBC FOUND_ROWS value of a no-op duplicate-key update.
            INSERT IGNORE INTO nx_hdpay_payin_order (
                merchant_order_id, amount_vnd, submission_status,
                request_hash, version, created_at, updated_at
            ) VALUES (
                #{merchantOrderId}, #{amountVnd}, 'PENDING',
                #{requestHash}, 0, NOW(), NOW()
            )
            """)
    int insertPending(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("amountVnd") BigDecimal amountVnd,
            @Param("requestHash") String requestHash);

    @Update("""
            UPDATE nx_hdpay_payin_order o
              JOIN nx_vietqr_intent i
                ON i.intent_no = o.merchant_order_id
               AND i.is_deleted = 0
               SET o.submission_status = 'SUBMIT_UNKNOWN',
                   o.last_error_code = 'HDPAY_SUBMISSION_STARTED',
                   o.version = o.version + 1, o.updated_at = NOW()
             WHERE o.merchant_order_id = #{merchantOrderId}
               AND o.submission_status = 'PENDING'
               AND i.status = 'AWAITING_PAYMENT'
               AND i.expires_at > NOW()
            """)
    int authorizeSubmissionIfIntentPayable(@Param("merchantOrderId") String merchantOrderId);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET submission_status = 'CREATED', payment_url = #{paymentUrl},
                   last_error_code = NULL, version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND submission_status = 'SUBMIT_UNKNOWN'
            """)
    int markCreated(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("paymentUrl") String paymentUrl);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET submission_status = 'SUBMIT_UNKNOWN', last_error_code = #{errorCode},
                   version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND submission_status = 'SUBMIT_UNKNOWN'
            """)
    int markSubmitUnknown(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("errorCode") String errorCode);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET submission_status = 'REJECTED', last_error_code = #{errorCode},
                   version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND submission_status = 'SUBMIT_UNKNOWN'
            """)
    int markRejected(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("errorCode") String errorCode);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET submission_status = 'CREATED', payment_url = #{paymentUrl},
                   provider_order_id = #{providerOrderId}, provider_status = #{providerStatus},
                   last_error_code = NULL, version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND submission_status = 'SUBMIT_UNKNOWN'
               AND (provider_order_id IS NULL OR provider_order_id = #{providerOrderId})
            """)
    int resolveSubmitUnknown(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("providerStatus") Integer providerStatus,
            @Param("paymentUrl") String paymentUrl);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET provider_order_id = #{providerOrderId}, provider_status = #{providerStatus},
                   last_error_code = #{errorCode}, version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND submission_status = 'SUBMIT_UNKNOWN'
               AND (provider_order_id IS NULL OR provider_order_id = #{providerOrderId})
            """)
    int observeSubmitUnknownTerminal(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("providerStatus") Integer providerStatus,
            @Param("errorCode") String errorCode);

    @Insert("""
            INSERT IGNORE INTO nx_hdpay_callback_inbox (
                payload_hash, merchant_order_id, provider_order_id,
                provider_status, amount_vnd, processing_status, claim_token, claimed_at,
                created_at, updated_at
            ) VALUES (
                #{payloadHash}, #{merchantOrderId}, #{providerOrderId},
                #{providerStatus}, #{amountVnd}, #{processingStatus}, #{claimToken},
                CASE WHEN #{claimToken} IS NULL THEN NULL ELSE NOW() END,
                NOW(), NOW()
            )
            """)
    int insertCallbackInbox(
            @Param("payloadHash") String payloadHash,
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("providerStatus") Integer providerStatus,
            @Param("amountVnd") BigDecimal amountVnd,
            @Param("processingStatus") String processingStatus,
            @Param("claimToken") String claimToken);

    @Select("""
            SELECT payload_hash AS payloadHash,
                   merchant_order_id AS merchantOrderId,
                   provider_order_id AS providerOrderId,
                   provider_status AS providerStatus,
                   amount_vnd AS amountVnd,
                   processing_status AS processingStatus,
                   claim_token AS claimToken,
                   claimed_at AS claimedAt,
                   provider_query_status AS providerQueryStatus,
                   result_code AS resultCode,
                   processed_at AS processedAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM nx_hdpay_callback_inbox
             WHERE payload_hash = #{payloadHash}
             LIMIT 1
             FOR UPDATE
            """)
    Map<String, Object> findCallbackInboxForUpdate(@Param("payloadHash") String payloadHash);

    @Select("""
            SELECT i.payload_hash AS payloadHash,
                   i.merchant_order_id AS merchantOrderId,
                   i.provider_order_id AS providerOrderId,
                   i.provider_status AS providerStatus,
                   i.amount_vnd AS amountVnd
              FROM nx_hdpay_callback_inbox i
              JOIN nx_hdpay_payin_order o
                ON o.merchant_order_id = i.merchant_order_id
             WHERE i.provider_status = 3
               AND i.processing_status IN ('PROCESSING', 'OBSERVED')
               AND i.updated_at <= #{staleBefore}
               AND o.settlement_status = 'UNSETTLED'
             ORDER BY i.updated_at ASC, i.id ASC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> listRecoverablePaidCallbacks(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit);

    @Update("""
            UPDATE nx_hdpay_callback_inbox
               SET processing_status = 'PROCESSING',
                   claim_token = #{claimToken},
                   claimed_at = NOW(),
                   provider_query_status = NULL,
                   result_code = NULL,
                   processed_at = NULL,
                   updated_at = NOW()
             WHERE payload_hash = #{payloadHash}
               AND processing_status IN ('PROCESSING', 'OBSERVED')
               AND updated_at <= #{staleBefore}
            """)
    int claimCallbackForRetry(
            @Param("payloadHash") String payloadHash,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("claimToken") String claimToken);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET provider_order_id = COALESCE(provider_order_id, #{providerOrderId}),
                   provider_status = CASE
                       WHEN provider_status IS NULL OR provider_status = 1 THEN #{providerStatus}
                       ELSE provider_status
                   END,
                   version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND (provider_order_id IS NULL OR provider_order_id = #{providerOrderId})
            """)
    int updateCallbackObservation(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("providerStatus") Integer providerStatus);

    @Update("""
            UPDATE nx_hdpay_callback_inbox
               SET processing_status = #{processingStatus},
                   claim_token = NULL,
                   claimed_at = NULL,
                   provider_query_status = #{providerQueryStatus},
                   result_code = #{resultCode},
                   processed_at = NOW(), updated_at = NOW()
             WHERE payload_hash = #{payloadHash}
            """)
    int markCallbackProcessed(
            @Param("payloadHash") String payloadHash,
            @Param("processingStatus") String processingStatus,
            @Param("providerQueryStatus") Integer providerQueryStatus,
            @Param("resultCode") String resultCode);

    @Update("""
            UPDATE nx_hdpay_callback_inbox
               SET processing_status = #{processingStatus},
                   claim_token = NULL,
                   claimed_at = NULL,
                   provider_query_status = #{providerQueryStatus},
                   result_code = #{resultCode},
                   processed_at = NOW(), updated_at = NOW()
             WHERE payload_hash = #{payloadHash}
               AND processing_status = 'PROCESSING'
               AND claim_token = #{claimToken}
            """)
    int markCallbackProcessedOwned(
            @Param("payloadHash") String payloadHash,
            @Param("claimToken") String claimToken,
            @Param("processingStatus") String processingStatus,
            @Param("providerQueryStatus") Integer providerQueryStatus,
            @Param("resultCode") String resultCode);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET provider_order_id = COALESCE(provider_order_id, #{providerOrderId}),
                   provider_status = #{providerStatus},
                   settlement_status = 'CREDITED', settled_usdt = #{settledUsdt},
                   wallet_ledger_biz_no = #{walletLedgerBizNo}, settled_at = NOW(),
                   last_error_code = NULL, version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND settlement_status = 'UNSETTLED'
               AND (provider_order_id IS NULL OR provider_order_id = #{providerOrderId})
            """)
    int markSettlementCredited(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("providerStatus") Integer providerStatus,
            @Param("settledUsdt") BigDecimal settledUsdt,
            @Param("walletLedgerBizNo") String walletLedgerBizNo);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET provider_order_id = COALESCE(provider_order_id, #{providerOrderId}),
                   provider_status = #{providerStatus},
                   settlement_status = 'MANUAL_REVIEW', last_error_code = #{reason},
                   version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND settlement_status <> 'CREDITED'
               AND (provider_order_id IS NULL OR provider_order_id = #{providerOrderId})
            """)
    int markSettlementReview(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("providerStatus") Integer providerStatus,
            @Param("reason") String reason);

    @Update("""
            UPDATE nx_hdpay_payin_order
               SET last_error_code = #{reason}, version = version + 1, updated_at = NOW()
             WHERE merchant_order_id = #{merchantOrderId}
               AND settlement_status = 'CREDITED'
               AND (provider_order_id IS NULL OR provider_order_id = #{providerOrderId})
            """)
    int markPostCreditReview(
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("reason") String reason);

    @Insert("""
            INSERT IGNORE INTO nx_hdpay_settlement_review (
                review_no, merchant_order_id, provider_order_id,
                callback_payload_hash, reason, status,
                created_at, updated_at
            ) VALUES (
                #{payloadHash}, #{merchantOrderId}, #{providerOrderId},
                #{payloadHash}, #{reason}, 'OPEN', NOW(), NOW()
            )
            """)
    int insertSettlementReview(
            @Param("payloadHash") String payloadHash,
            @Param("merchantOrderId") String merchantOrderId,
            @Param("providerOrderId") String providerOrderId,
            @Param("reason") String reason);

    @Insert("""
            INSERT IGNORE INTO nx_notification (
                biz_no, user_id, type, priority, title, body,
                cta_label, cta_href, read_flag, push_status, push_attempts,
                next_push_at, created_at, updated_at, is_deleted
            )
            SELECT #{bizNo}, u.id, 'WALLET', 'high',
                   CASE
                     WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN 'Nạp tiền thành công'
                     WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN '充值已到账'
                     ELSE 'Deposit credited'
                   END,
                   CASE
                     WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN CONCAT(#{amountUsdt}, ' USDT đã được ghi có vào ví của bạn.')
                     WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN CONCAT(#{amountUsdt}, ' USDT 已到账。')
                     ELSE CONCAT(#{amountUsdt}, ' USDT has been credited to your wallet.')
                   END,
                   NULL, NULL, 0, 'PENDING', 0, NOW(), NOW(), NOW(), 0
              FROM nx_user u
             WHERE u.id = #{userId} AND u.is_deleted = 0
            """)
    int insertDepositNotification(
            @Param("bizNo") String bizNo,
            @Param("userId") Long userId,
            @Param("amountUsdt") BigDecimal amountUsdt);
}
