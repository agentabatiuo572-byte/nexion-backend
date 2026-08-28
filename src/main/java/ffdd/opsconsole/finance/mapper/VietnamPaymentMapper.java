package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.DepositOrderEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VietnamPaymentMapper extends BaseMapper<DepositOrderEntity> {

    @Select("""
            SELECT id, tolerance_vnd AS toleranceVnd, grace_minutes AS graceMinutes,
                   per_tx_limit_usd AS perTxLimitUsd,
                   trc20_confirmations AS trc20Confirmations,
                   erc20_confirmations AS erc20Confirmations,
                   bep20_confirmations AS bep20Confirmations,
                   rotation_strategy AS rotationStrategy, version, updated_at AS updatedAt
              FROM nx_vietqr_config
             WHERE id = 1 AND is_deleted = 0
            """)
    Map<String, Object> findVietQrConfig();

    @Select("""
            SELECT id, bank_code AS bankCode, bank_name AS bankName,
                   CONCAT(LEFT(account_holder, 4), ' ***') AS holderMasked,
                   account_number_last4 AS accountLast4,
                   daily_cap_vnd AS dailyCapVnd,
                   CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                        THEN received_today_vnd ELSE 0 END AS receivedTodayVnd,
                   status, fuse_reason AS fuseReason, version, updated_at AS updatedAt
              FROM nx_vietqr_bank_account
             WHERE is_deleted = 0
             ORDER BY id ASC
            """)
    List<Map<String, Object>> listVietQrBankAccounts();

    @Select("""
            SELECT id,
                   account_number_encrypted AS accountNumberEncrypted,
                   account_number_hash AS accountNumberHash
              FROM nx_vietqr_bank_account
             WHERE status = 'ACTIVE' AND is_deleted = 0
             ORDER BY id ASC
            """)
    List<Map<String, Object>> listActiveVietQrAccountsForKeyValidation();

    @Select("""
            SELECT COUNT(1)
              FROM nx_vietqr_reconciliation
             WHERE is_deleted = 0
               AND (#{viewType} IS NULL OR view_type = #{viewType})
            """)
    long countVietQrReconciliations(@Param("viewType") String viewType);

    @Select("""
            SELECT r.id, r.reconciliation_no AS reconciliationNo, r.intent_no AS intentNo,
                   r.user_id AS userId, r.bank_account_id AS bankAccountId,
                   i.bank_account_id AS assignedBankAccountId, i.memo_code AS memoCode,
                   CASE WHEN r.view_type <> 'MISMATCH' THEN NULL
                        WHEN i.id IS NULL OR r.bank_account_id IS NULL OR i.bank_account_id IS NULL THEN 'UNKNOWN'
                        WHEN r.bank_account_id <> i.bank_account_id
                             AND r.received_vnd <> i.payable_vnd THEN 'BANK_ACCOUNT_AND_AMOUNT'
                        WHEN r.bank_account_id <> i.bank_account_id THEN 'BANK_ACCOUNT'
                        ELSE 'AMOUNT' END AS mismatchReason,
                   r.view_type AS viewType, r.status, r.payable_vnd AS payableVnd,
                   r.received_vnd AS receivedVnd,
                   r.locked_fx_rate_vnd_per_usdt AS lockedFxRateVndPerUsdt,
                   r.credited_usdt AS creditedUsdt, r.payment_reference AS paymentReference,
                   r.note, r.expires_at AS expiresAt, r.received_at AS receivedAt,
                   r.intent_transition_required AS intentTransitionRequired,
                   r.version, r.created_at AS createdAt, r.updated_at AS updatedAt
              FROM nx_vietqr_reconciliation r
              LEFT JOIN nx_vietqr_intent i ON i.intent_no = r.intent_no AND i.is_deleted = 0
             WHERE r.is_deleted = 0
               AND (#{viewType} IS NULL OR r.view_type = #{viewType})
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<Map<String, Object>> listVietQrReconciliations(
            @Param("viewType") String viewType,
            @Param("pageSize") int pageSize,
            @Param("offset") int offset);

    @Select("""
            SELECT COALESCE(SUM(received_vnd / NULLIF(locked_fx_rate_vnd_per_usdt, 0)), 0)
              FROM nx_vietqr_reconciliation
             WHERE is_deleted = 0
               AND status = 'OPEN'
               AND received_vnd > 0
            """)
    BigDecimal sumPendingUnverifiedDepositUsdt();

    @Select("""
            SELECT id, reconciliation_no AS reconciliationNo, intent_no AS intentNo,
                   user_id AS userId, bank_account_id AS bankAccountId,
                   view_type AS viewType, status, payable_vnd AS payableVnd,
                   received_vnd AS receivedVnd, payment_reference AS paymentReference,
                   locked_fx_rate_vnd_per_usdt AS lockedFxRateVndPerUsdt,
                   received_at AS receivedAt,
                   intent_transition_required AS intentTransitionRequired,
                   version
              FROM nx_vietqr_reconciliation
             WHERE id = #{id} AND is_deleted = 0
             FOR UPDATE
            """)
    Map<String, Object> findVietQrReconciliationForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE nx_vietqr_reconciliation
               SET status = #{status}, view_type = #{viewType},
                   user_id = #{userId}, intent_no = #{intentNo},
                   credited_usdt = #{creditedUsdt}, note = #{note},
                   version = version + 1, updated_at = NOW()
             WHERE id = #{id} AND version = #{expectedVersion}
               AND status = 'OPEN' AND is_deleted = 0
            """)
    int completeVietQrReconciliation(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("status") String status,
            @Param("viewType") String viewType,
            @Param("userId") Long userId,
            @Param("intentNo") String intentNo,
            @Param("creditedUsdt") BigDecimal creditedUsdt,
            @Param("note") String note);

    @Insert("""
            INSERT INTO nx_vietqr_reconciliation (
                reconciliation_no, intent_no, user_id, bank_account_id,
                view_type, status, payable_vnd, received_vnd,
                locked_fx_rate_vnd_per_usdt, credited_usdt,
                payment_reference, note, expires_at, received_at,
                intent_transition_required,
                version, created_at, updated_at, is_deleted
            ) VALUES (
                #{reconciliationNo}, #{intentNo}, #{userId}, #{bankAccountId},
                #{viewType}, 'OPEN', #{payableVnd}, #{receivedVnd},
                #{lockedFxRateVndPerUsdt}, 0,
                #{paymentReference}, #{note}, #{expiresAt}, #{receivedAt},
                #{intentTransitionRequired},
                0, NOW(), NOW(), 0
            )
            """)
    int insertVietQrReceipt(
            @Param("reconciliationNo") String reconciliationNo,
            @Param("intentNo") String intentNo,
            @Param("userId") Long userId,
            @Param("bankAccountId") Long bankAccountId,
            @Param("viewType") String viewType,
            @Param("payableVnd") BigDecimal payableVnd,
            @Param("receivedVnd") BigDecimal receivedVnd,
            @Param("lockedFxRateVndPerUsdt") BigDecimal lockedFxRateVndPerUsdt,
            @Param("paymentReference") String paymentReference,
            @Param("note") String note,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("receivedAt") LocalDateTime receivedAt,
            @Param("intentTransitionRequired") boolean intentTransitionRequired);

    @Select("""
            SELECT id, reconciliation_no AS reconciliationNo, intent_no AS intentNo,
                   user_id AS userId, bank_account_id AS bankAccountId,
                   view_type AS viewType, status, payable_vnd AS payableVnd,
                   received_vnd AS receivedVnd,
                   locked_fx_rate_vnd_per_usdt AS lockedFxRateVndPerUsdt,
                   payment_reference AS paymentReference, received_at AS receivedAt,
                   intent_transition_required AS intentTransitionRequired,
                   version
              FROM nx_vietqr_reconciliation
             WHERE payment_reference = #{paymentReference} AND is_deleted = 0
             LIMIT 1
            """)
    Map<String, Object> findVietQrReceiptByPaymentReference(
            @Param("paymentReference") String paymentReference);

    @Select("""
            SELECT usdt_available AS usdtAvailable, version
              FROM nx_user_wallet
             WHERE user_id = #{userId} AND is_deleted = 0
             FOR UPDATE
            """)
    Map<String, Object> findUsdtWalletForUpdate(@Param("userId") Long userId);

    @Select("""
            SELECT id, daily_cap_vnd AS dailyCapVnd,
                   CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                        THEN received_today_vnd ELSE 0 END AS receivedTodayVnd,
                   status, version
              FROM nx_vietqr_bank_account
             WHERE id = #{id} AND is_deleted = 0
             FOR UPDATE
            """)
    Map<String, Object> findVietQrBankAccountForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE nx_vietqr_bank_account
               SET status = CASE
                       WHEN #{receivedBusinessDate} = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                        AND (CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                                  THEN received_today_vnd ELSE 0 END) + #{receivedVnd}
                            > daily_cap_vnd
                       THEN 'FUSED'
                       ELSE status
                   END,
                   fuse_reason = CASE
                       WHEN #{receivedBusinessDate} = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                        AND (CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                                  THEN received_today_vnd ELSE 0 END) + #{receivedVnd}
                            > daily_cap_vnd
                       THEN 'DAILY_CAP_EXCEEDED_AFTER_RECEIPT'
                       ELSE fuse_reason
                   END,
                   received_today_vnd = CASE
                       WHEN #{receivedBusinessDate} = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                       THEN CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                                 THEN received_today_vnd + #{receivedVnd}
                                 ELSE #{receivedVnd} END
                       ELSE received_today_vnd
                   END,
                   received_business_date = CASE
                       WHEN #{receivedBusinessDate} = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                       THEN DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                       ELSE received_business_date
                   END,
                   version = version + 1, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0
            """)
    int addVietQrBankReceivedToday(
            @Param("id") Long id,
            @Param("receivedVnd") BigDecimal receivedVnd,
            @Param("receivedBusinessDate") LocalDate receivedBusinessDate);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available = usdt_available + #{amount},
                   cumulative_deposit_usdt = cumulative_deposit_usdt + #{amount},
                   version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int creditUsdtWallet(
            @Param("userId") Long userId,
            @Param("amount") BigDecimal amount,
            @Param("expectedVersion") Long expectedVersion);

    @Insert("""
            INSERT INTO nx_wallet_ledger (
                biz_no, user_id, biz_type, asset, direction, amount,
                balance_after, status, remark, created_at, updated_at, is_deleted
            ) VALUES (
                #{bizNo}, #{userId}, 'VIETQR_DEPOSIT', 'USDT', 'IN', #{amount},
                #{balanceAfter}, 'SUCCESS', #{remark}, NOW(), NOW(), 0
            )
            """)
    int insertVietQrWalletLedger(
            @Param("bizNo") String bizNo,
            @Param("userId") Long userId,
            @Param("amount") BigDecimal amount,
            @Param("balanceAfter") BigDecimal balanceAfter,
            @Param("remark") String remark);

    @Insert("""
            INSERT INTO nx_vietqr_bank_account (
                bank_code, bank_name, account_holder, account_number_encrypted,
                account_number_hash, account_number_last4,
                daily_cap_vnd, received_today_vnd, received_business_date, status,
                created_at, updated_at, is_deleted, version
            ) VALUES (
                #{bankCode}, #{bankName}, #{accountHolder}, #{accountNumberEncrypted},
                #{accountNumberHash}, #{accountNumberLast4},
                #{dailyCapVnd}, 0, DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR)),
                'ACTIVE', NOW(), NOW(), 0, 0
            )
            """)
    int insertVietQrBankAccount(
            @Param("bankCode") String bankCode,
            @Param("bankName") String bankName,
            @Param("accountHolder") String accountHolder,
            @Param("accountNumberEncrypted") String accountNumberEncrypted,
            @Param("accountNumberHash") String accountNumberHash,
            @Param("accountNumberLast4") String accountNumberLast4,
            @Param("dailyCapVnd") BigDecimal dailyCapVnd);

    @Select("""
            SELECT id, bank_code AS bankCode, bank_name AS bankName,
                   CONCAT(LEFT(account_holder, 4), ' ***') AS holderMasked,
                   account_number_last4 AS accountLast4,
                   daily_cap_vnd AS dailyCapVnd,
                   CASE WHEN received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                        THEN received_today_vnd ELSE 0 END AS receivedTodayVnd,
                   status, fuse_reason AS fuseReason, version, updated_at AS updatedAt
              FROM nx_vietqr_bank_account
             WHERE id = #{id} AND is_deleted = 0
            """)
    Map<String, Object> findVietQrBankAccount(@Param("id") Long id);

    @Update("""
            UPDATE nx_vietqr_bank_account
               SET status = CASE
                       WHEN #{action} = 'ENABLE' THEN 'ACTIVE'
                       WHEN #{action} = 'DISABLE' THEN 'DISABLED'
                       WHEN #{action} = 'RECOVER' THEN 'ACTIVE'
                       ELSE status
                   END,
                   fuse_reason = CASE WHEN #{action} = 'RECOVER' THEN NULL ELSE fuse_reason END,
                   daily_cap_vnd = CASE WHEN #{action} = 'UPDATE_CAP' THEN #{dailyCapVnd} ELSE daily_cap_vnd END,
                   version = version + 1, updated_at = NOW()
             WHERE id = #{id} AND version = #{expectedVersion} AND is_deleted = 0
               AND (
                    (#{action} = 'ENABLE' AND status = 'DISABLED')
                 OR (#{action} = 'DISABLE' AND status = 'ACTIVE')
                 OR (#{action} = 'RECOVER' AND status = 'FUSED')
                 OR (#{action} = 'UPDATE_CAP')
               )
            """)
    int updateVietQrBankAccount(
            @Param("id") Long id,
            @Param("action") String action,
            @Param("dailyCapVnd") BigDecimal dailyCapVnd,
            @Param("expectedVersion") Long expectedVersion);

    @Update("""
            UPDATE nx_vietqr_config
               SET tolerance_vnd = #{toleranceVnd}, grace_minutes = #{graceMinutes},
                   per_tx_limit_usd = #{perTxLimitUsd},
                   trc20_confirmations = #{trc20Confirmations},
                   erc20_confirmations = #{erc20Confirmations},
                   bep20_confirmations = #{bep20Confirmations},
                   rotation_strategy = #{rotationStrategy},
                   updated_by = #{operator}, update_reason = #{reason},
                   version = version + 1, updated_at = NOW()
             WHERE id = 1 AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int updateVietQrConfig(
            @Param("toleranceVnd") BigDecimal toleranceVnd,
            @Param("graceMinutes") Integer graceMinutes,
            @Param("perTxLimitUsd") BigDecimal perTxLimitUsd,
            @Param("trc20Confirmations") Integer trc20Confirmations,
            @Param("erc20Confirmations") Integer erc20Confirmations,
            @Param("bep20Confirmations") Integer bep20Confirmations,
            @Param("rotationStrategy") String rotationStrategy,
            @Param("expectedVersion") Long expectedVersion,
            @Param("operator") String operator,
            @Param("reason") String reason);

    @Select("""
            SELECT config_code AS configCode,
                   base_rate_vnd_per_usdt AS baseRateVndPerUsdt,
                   buy_spread_pct AS buySpreadPct,
                   lock_window_minutes AS lockWindowMinutes,
                   version, updated_by AS updatedBy, update_reason AS updateReason,
                   updated_at AS updatedAt
              FROM nx_finance_fx_quote_config
             WHERE config_code = 'VND_USDT' AND is_deleted = 0
            """)
    Map<String, Object> findFxQuoteConfig();

    @Select("""
            SELECT id, base_rate_before AS beforeBaseRateVndPerUsdt,
                   base_rate_after AS baseRateVndPerUsdt,
                   buy_spread_before AS beforeBuySpreadPct,
                   buy_spread_after AS buySpreadPct,
                   lock_window_before AS beforeLockWindowMinutes,
                   lock_window_after AS lockWindowMinutes,
                   operator, reason, created_at AS createdAt
              FROM nx_finance_fx_quote_history
             WHERE is_deleted = 0
             ORDER BY created_at DESC, id DESC
             LIMIT 50
            """)
    List<Map<String, Object>> listFxQuoteHistory();

    @Update("""
            UPDATE nx_finance_fx_quote_config
               SET base_rate_vnd_per_usdt = #{baseRateVndPerUsdt},
                   buy_spread_pct = #{buySpreadPct},
                   lock_window_minutes = #{lockWindowMinutes},
                   updated_by = #{operator}, update_reason = #{reason},
                   version = version + 1, updated_at = NOW()
             WHERE config_code = 'VND_USDT'
               AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int updateFxQuoteConfig(
            @Param("baseRateVndPerUsdt") BigDecimal baseRateVndPerUsdt,
            @Param("buySpreadPct") BigDecimal buySpreadPct,
            @Param("lockWindowMinutes") Integer lockWindowMinutes,
            @Param("expectedVersion") Long expectedVersion,
            @Param("operator") String operator,
            @Param("reason") String reason);

    @Insert("""
            INSERT INTO nx_finance_fx_quote_history (
                base_rate_before, base_rate_after,
                buy_spread_before, buy_spread_after,
                lock_window_before, lock_window_after,
                operator, reason, idempotency_key, created_at, is_deleted
            ) VALUES (
                #{beforeBaseRateVndPerUsdt}, #{baseRateVndPerUsdt},
                #{beforeBuySpreadPct}, #{buySpreadPct},
                #{beforeLockWindowMinutes}, #{lockWindowMinutes},
                #{operator}, #{reason}, #{idempotencyKey}, NOW(), 0
            )
            """)
    int insertFxQuoteHistory(
            @Param("beforeBaseRateVndPerUsdt") BigDecimal beforeBaseRateVndPerUsdt,
            @Param("baseRateVndPerUsdt") BigDecimal baseRateVndPerUsdt,
            @Param("beforeBuySpreadPct") BigDecimal beforeBuySpreadPct,
            @Param("buySpreadPct") BigDecimal buySpreadPct,
            @Param("beforeLockWindowMinutes") Integer beforeLockWindowMinutes,
            @Param("lockWindowMinutes") Integer lockWindowMinutes,
            @Param("operator") String operator,
            @Param("reason") String reason,
            @Param("idempotencyKey") String idempotencyKey);
}
