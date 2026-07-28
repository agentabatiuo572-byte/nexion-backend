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
public interface AppVietQrIntentMapper extends BaseMapper<Object> {

    String INTENT_COLUMNS = """
            i.intent_no AS intentNo, i.user_id AS userId,
            i.create_idempotency_key AS createIdempotencyKey,
            i.create_request_hash AS createRequestHash,
            i.requested_usdt AS requestedUsdt, i.payable_vnd AS payableVnd,
            i.credited_usdt AS creditedUsdt, i.received_vnd AS receivedVnd,
            i.locked_fx_rate_vnd_per_usdt AS lockedFxRateVndPerUsdt,
            i.fx_quote_version AS fxQuoteVersion, i.bank_account_id AS bankAccountId,
            i.memo_code AS memoCode, i.status, i.expires_at AS expiresAt,
            i.matched_at AS matchedAt, i.cancel_idempotency_key AS cancelIdempotencyKey,
            i.cancel_request_hash AS cancelRequestHash, i.version,
            i.created_at AS createdAt, i.updated_at AS updatedAt,
            b.bank_name AS bankName, b.account_holder AS accountHolder,
            b.account_number_encrypted AS accountNumberEncrypted,
            b.account_number_hash AS accountNumberHash,
            b.account_number_last4 AS accountNumberLast4,
            b.status AS bankAccountStatus
            """;

    @Select("""
            SELECT id, tolerance_vnd AS toleranceVnd, grace_minutes AS graceMinutes,
                   per_tx_limit_usd AS perTxLimitUsd, rotation_strategy AS rotationStrategy,
                   version, updated_at AS updatedAt
              FROM nx_vietqr_config
             WHERE id = 1 AND is_deleted = 0
            """)
    Map<String, Object> findVietQrConfig();

    @Select("""
            SELECT config_code AS configCode,
                   base_rate_vnd_per_usdt AS baseRateVndPerUsdt,
                   buy_spread_pct AS buySpreadPct,
                   lock_window_minutes AS lockWindowMinutes,
                   version, updated_at AS updatedAt
              FROM nx_finance_fx_quote_config
             WHERE config_code = 'VND_USDT' AND is_deleted = 0
            """)
    Map<String, Object> findFxQuoteConfig();

    @Select("""
            SELECT COUNT(1)
              FROM nx_vietqr_bank_account
             WHERE status = 'ACTIVE' AND is_deleted = 0
               AND (
                   CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                        THEN received_today_vnd ELSE 0 END
                   + COALESCE((
                       SELECT SUM(i.payable_vnd)
                         FROM nx_vietqr_intent i
                        WHERE i.bank_account_id = nx_vietqr_bank_account.id
                          AND i.status = 'AWAITING_PAYMENT'
                          AND i.expires_at > NOW() AND i.is_deleted = 0
                   ), 0)
               ) < daily_cap_vnd
            """)
    long countAvailableBankAccounts();

    @Select("""
            SELECT id, bank_name AS bankName, account_holder AS accountHolder,
                   account_number_encrypted AS accountNumberEncrypted,
                   account_number_last4 AS accountNumberLast4,
                   daily_cap_vnd AS dailyCapVnd,
                   CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                        THEN received_today_vnd ELSE 0 END AS receivedTodayVnd,
                   version
              FROM nx_vietqr_bank_account
             WHERE status = 'ACTIVE' AND is_deleted = 0
             ORDER BY id ASC
             FOR UPDATE
            """)
    List<Map<String, Object>> listActiveBankAccountsForUpdate();

    @Select("""
            SELECT bank_account_id
              FROM nx_vietqr_intent
             WHERE is_deleted = 0
             ORDER BY id DESC
             LIMIT 1
            """)
    Long findLastAssignedBankAccountId();

    @Select("""
            SELECT COALESCE(SUM(payable_vnd), 0)
              FROM nx_vietqr_intent
             WHERE bank_account_id = #{bankAccountId}
               AND status = 'AWAITING_PAYMENT'
               AND expires_at > NOW()
               AND is_deleted = 0
            """)
    BigDecimal sumActiveReservedVnd(@Param("bankAccountId") Long bankAccountId);

    @Select("""
            SELECT COUNT(1)
              FROM nx_vietqr_intent
             WHERE user_id = #{userId}
               AND status = 'AWAITING_PAYMENT'
               AND expires_at > NOW()
               AND is_deleted = 0
            """)
    long countActiveIntentsForUser(@Param("userId") Long userId);

    @Select("""
            SELECT id
              FROM nx_user
             WHERE id = #{userId} AND status = 'ACTIVE' AND is_deleted = 0
             FOR UPDATE
            """)
    Long lockActiveUserForIntentCreation(@Param("userId") Long userId);

    @Select("SELECT " + INTENT_COLUMNS + """
              FROM nx_vietqr_intent i
              LEFT JOIN nx_vietqr_bank_account b ON b.id = i.bank_account_id
             WHERE i.user_id = #{userId}
               AND i.create_idempotency_key = #{idempotencyKey}
               AND i.is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    Map<String, Object> findIntentByCreateKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO nx_vietqr_intent (
                intent_no, user_id, create_idempotency_key, create_request_hash,
                requested_usdt, payable_vnd, credited_usdt,
                locked_fx_rate_vnd_per_usdt, fx_quote_version,
                bank_account_id, memo_code, status, expires_at,
                version, created_at, updated_at, is_deleted
            ) VALUES (
                #{intentNo}, #{userId}, #{idempotencyKey}, #{requestHash},
                #{requestedUsdt}, #{payableVnd}, 0,
                #{fxRate}, #{fxQuoteVersion},
                #{bankAccountId}, #{memoCode}, 'AWAITING_PAYMENT', #{expiresAt},
                0, NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIntent(
            @Param("intentNo") String intentNo,
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("requestedUsdt") BigDecimal requestedUsdt,
            @Param("payableVnd") BigDecimal payableVnd,
            @Param("fxRate") BigDecimal fxRate,
            @Param("fxQuoteVersion") Long fxQuoteVersion,
            @Param("bankAccountId") Long bankAccountId,
            @Param("memoCode") String memoCode,
            @Param("expiresAt") LocalDateTime expiresAt);

    @Insert("""
            INSERT INTO nx_vietqr_reconciliation (
                reconciliation_no, intent_no, user_id, bank_account_id,
                view_type, status, payable_vnd, received_vnd,
                locked_fx_rate_vnd_per_usdt, credited_usdt,
                payment_reference, note, expires_at, received_at,
                version, created_at, updated_at, is_deleted
            )
            SELECT CONCAT('APP-', intent_no), intent_no, user_id, bank_account_id,
                   'INFLIGHT', 'OPEN', payable_vnd, NULL,
                   locked_fx_rate_vnd_per_usdt, 0,
                   NULL, 'APP_INTENT_CREATED', expires_at, NULL,
                   0, NOW(), NOW(), 0
              FROM nx_vietqr_intent
             WHERE intent_no = #{intentNo}
               AND status = 'AWAITING_PAYMENT' AND expires_at > NOW()
               AND is_deleted = 0
            ON DUPLICATE KEY UPDATE reconciliation_no = reconciliation_no
            """)
    int ensureInFlightReconciliation(@Param("intentNo") String intentNo);

    @Update("""
            UPDATE nx_vietqr_intent
               SET status = 'EXPIRED', version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND intent_no = #{intentNo}
               AND status = 'AWAITING_PAYMENT' AND expires_at <= NOW()
               AND is_deleted = 0
            """)
    int expireIntentForUser(
            @Param("userId") Long userId,
            @Param("intentNo") String intentNo);

    @Update("""
            UPDATE nx_vietqr_intent
               SET status = 'EXPIRED', version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId}
               AND status = 'AWAITING_PAYMENT' AND expires_at <= NOW()
               AND is_deleted = 0
            """)
    int expireIntentsForUser(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_vietqr_intent
               SET status = 'EXPIRED', version = version + 1, updated_at = NOW()
             WHERE status = 'AWAITING_PAYMENT' AND expires_at <= NOW()
               AND is_deleted = 0
            """)
    int expireAllIntents();

    @Update("""
            UPDATE nx_vietqr_reconciliation r
            JOIN nx_vietqr_intent i ON i.intent_no = r.intent_no AND i.is_deleted = 0
               SET r.is_deleted = 1, r.note = i.status, r.updated_at = NOW()
             WHERE i.user_id = #{userId}
               AND i.status IN (
                   'RECEIPT_REVIEW','MISMATCH_REVIEW','LATE_REVIEW',
                   'EXPIRED','CANCELLED','CREDITED','RETURNED')
               AND r.reconciliation_no = CONCAT('APP-', i.intent_no)
               AND r.view_type = 'INFLIGHT' AND r.status = 'OPEN' AND r.is_deleted = 0
            """)
    int closeInactiveInFlightReconciliationsForUser(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_vietqr_reconciliation r
            JOIN nx_vietqr_intent i ON i.intent_no = r.intent_no AND i.is_deleted = 0
               SET r.is_deleted = 1, r.note = i.status, r.updated_at = NOW()
             WHERE i.status IN (
                   'RECEIPT_REVIEW','MISMATCH_REVIEW','LATE_REVIEW',
                   'EXPIRED','CANCELLED','CREDITED','RETURNED')
               AND r.reconciliation_no = CONCAT('APP-', i.intent_no)
               AND r.view_type = 'INFLIGHT' AND r.status = 'OPEN' AND r.is_deleted = 0
            """)
    int closeAllInactiveInFlightReconciliations();

    @Update("""
            UPDATE nx_vietqr_reconciliation
               SET is_deleted = 1, note = #{reason}, updated_at = NOW()
             WHERE reconciliation_no = CONCAT('APP-', #{intentNo})
               AND view_type = 'INFLIGHT' AND status = 'OPEN' AND is_deleted = 0
            """)
    int closeInFlightReconciliation(
            @Param("intentNo") String intentNo,
            @Param("reason") String reason);

    @Update("""
            UPDATE nx_vietqr_intent
               SET status = 'CANCELLED', version = version + 1, updated_at = NOW()
             WHERE bank_account_id = #{bankAccountId}
               AND status = 'AWAITING_PAYMENT'
               AND (#{excludedIntentNo} IS NULL OR intent_no <> #{excludedIntentNo})
               AND is_deleted = 0
            """)
    int cancelAwaitingIntentsForFusedAccount(
            @Param("bankAccountId") Long bankAccountId,
            @Param("excludedIntentNo") String excludedIntentNo);

    @Update("""
            UPDATE nx_vietqr_reconciliation r
            JOIN nx_vietqr_intent i
              ON i.intent_no = r.intent_no AND i.is_deleted = 0
               SET r.is_deleted = 1,
                   r.note = 'BANK_ACCOUNT_FUSED',
                   r.updated_at = NOW()
             WHERE i.bank_account_id = #{bankAccountId}
               AND i.status = 'CANCELLED'
               AND (#{excludedIntentNo} IS NULL OR i.intent_no <> #{excludedIntentNo})
               AND r.reconciliation_no = CONCAT('APP-', i.intent_no)
               AND r.view_type = 'INFLIGHT'
               AND r.status = 'OPEN'
               AND r.is_deleted = 0
            """)
    int closeCancelledInFlightReconciliationsForFusedAccount(
            @Param("bankAccountId") Long bankAccountId,
            @Param("excludedIntentNo") String excludedIntentNo);

    @Select("SELECT " + INTENT_COLUMNS + """
              FROM nx_vietqr_intent i
              LEFT JOIN nx_vietqr_bank_account b ON b.id = i.bank_account_id
             WHERE i.user_id = #{userId}
               AND i.intent_no = #{intentNo}
               AND i.is_deleted = 0
             LIMIT 1
            """)
    Map<String, Object> findIntentForUser(
            @Param("userId") Long userId,
            @Param("intentNo") String intentNo);

    @Select("SELECT " + INTENT_COLUMNS + """
              FROM nx_vietqr_intent i
              LEFT JOIN nx_vietqr_bank_account b ON b.id = i.bank_account_id
             WHERE i.user_id = #{userId} AND i.is_deleted = 0
             ORDER BY i.created_at DESC, i.id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> listIntentsForUser(
            @Param("userId") Long userId,
            @Param("limit") int limit);

    @Update("""
            UPDATE nx_vietqr_intent
               SET status = 'CANCELLED',
                   cancel_idempotency_key = #{idempotencyKey},
                   cancel_request_hash = #{requestHash},
                   version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND intent_no = #{intentNo}
               AND status = 'AWAITING_PAYMENT' AND expires_at > NOW()
               AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int cancelIntent(
            @Param("userId") Long userId,
            @Param("intentNo") String intentNo,
            @Param("expectedVersion") Long expectedVersion,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash);

    @Select("SELECT " + INTENT_COLUMNS + """
              FROM nx_vietqr_intent i
              LEFT JOIN nx_vietqr_bank_account b ON b.id = i.bank_account_id
             WHERE i.intent_no = #{intentNo} AND i.is_deleted = 0
             FOR UPDATE
            """)
    Map<String, Object> findIntentForUpdate(@Param("intentNo") String intentNo);

    @Select("SELECT " + INTENT_COLUMNS + """
              FROM nx_vietqr_intent i
              LEFT JOIN nx_vietqr_bank_account b ON b.id = i.bank_account_id
             WHERE i.memo_code = #{memoCode} AND i.is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    Map<String, Object> findIntentByMemoForUpdate(@Param("memoCode") String memoCode);

    @Update("""
            UPDATE nx_vietqr_intent
               SET status = 'CANCELLED', version = version + 1, updated_at = NOW()
             WHERE bank_account_id = #{bankAccountId}
               AND status = 'AWAITING_PAYMENT' AND is_deleted = 0
            """)
    int cancelActiveIntentsForBankAccount(@Param("bankAccountId") Long bankAccountId);

    @Update("""
            UPDATE nx_vietqr_reconciliation r
            JOIN nx_vietqr_intent i ON i.intent_no = r.intent_no AND i.is_deleted = 0
               SET r.is_deleted = 1, r.note = 'ACCOUNT_DISABLED', r.updated_at = NOW()
             WHERE i.bank_account_id = #{bankAccountId}
               AND i.status = 'CANCELLED'
               AND r.view_type = 'INFLIGHT' AND r.status = 'OPEN' AND r.is_deleted = 0
            """)
    int closeInFlightReconciliationsForBankAccount(@Param("bankAccountId") Long bankAccountId);

    @Update("""
            UPDATE nx_vietqr_intent
               SET status = #{status}, received_vnd = #{receivedVnd},
                   credited_usdt = #{creditedUsdt}, matched_at = #{matchedAt},
                   version = version + 1, updated_at = NOW()
             WHERE intent_no = #{intentNo} AND version = #{expectedVersion}
               AND status = #{expectedStatus} AND is_deleted = 0
            """)
    int transitionIntent(
            @Param("intentNo") String intentNo,
            @Param("expectedVersion") Long expectedVersion,
            @Param("expectedStatus") String expectedStatus,
            @Param("status") String status,
            @Param("receivedVnd") BigDecimal receivedVnd,
            @Param("creditedUsdt") BigDecimal creditedUsdt,
            @Param("matchedAt") LocalDateTime matchedAt);
}
