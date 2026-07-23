package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.TopupFeeBufferSnapshot;
import ffdd.opsconsole.finance.domain.TopupChargebackEventReceipt;
import ffdd.opsconsole.finance.domain.TopupChargebackSource;
import ffdd.opsconsole.finance.domain.TopupFailureReceipt;
import ffdd.opsconsole.finance.domain.TopupAdmissionReceipt;
import ffdd.opsconsole.finance.domain.TopupProviderStatementReceipt;
import ffdd.opsconsole.finance.domain.TopupRiskLockSnapshot;
import ffdd.opsconsole.finance.domain.TopupSettlementReceipt;
import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.infrastructure.D1FinanceClosureEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface D1FinanceClosureMapper extends BaseMapper<D1FinanceClosureEntity> {

    @Select("""
            SELECT user_id AS userId, usdt_available AS usdtAvailable,
                   cumulative_deposit_usdt AS cumulativeDepositUsdt, version
              FROM nx_user_wallet
             WHERE user_id = #{userId} AND is_deleted = 0
             FOR UPDATE
            """)
    TopupWalletSnapshot selectWalletForUpdate(@Param("userId") Long userId);

    @Select("""
            SELECT balance_usdt AS balanceUsdt, version
              FROM nx_topup_fee_buffer_account
             WHERE id = 1
             FOR UPDATE
            """)
    TopupFeeBufferSnapshot selectFeeBufferForUpdate();

    @Select("SELECT COALESCE(balance_usdt, 0) FROM nx_topup_fee_buffer_account WHERE id = 1")
    BigDecimal feeBufferBalance();

    @Select("""
            SELECT COUNT(1) FROM nx_topup_cumulative_backfill_anomaly
             WHERE is_deleted=0 AND status='OPEN' AND reason_code='FEE_EVIDENCE_MISSING'
            """)
    long feeEvidenceAnomalyCount();

    @Select("""
            SELECT COUNT(1) FROM nx_topup_cumulative_backfill_anomaly
             WHERE is_deleted=0 AND status='OPEN'
               AND reason_code IN ('TREASURY_RESERVE_EVIDENCE_MISSING','TREASURY_RESERVE_EVIDENCE_MISMATCH',
                                   'TREASURY_REVERSAL_EVIDENCE_MISMATCH',
                                   'TREASURY_VALUATION_EVIDENCE_MISSING')
            """)
    long treasuryReserveAnomalyCount();

    @Select("""
            SELECT COUNT(1) FROM nx_topup_cumulative_backfill_anomaly
             WHERE is_deleted=0 AND status='OPEN'
            """)
    long historicalBackfillAnomalyCount();

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available = #{available}, cumulative_deposit_usdt = #{cumulative},
                   version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND is_deleted = 0 AND version = #{version}
            """)
    int updateWallet(@Param("userId") Long userId, @Param("available") BigDecimal available,
                     @Param("cumulative") BigDecimal cumulative, @Param("version") Long version);

    @Update("""
            UPDATE nx_topup_fee_buffer_account
               SET balance_usdt = #{balance}, version = version + 1, updated_at = NOW()
             WHERE id = 1 AND version = #{version}
            """)
    int updateFeeBuffer(@Param("balance") BigDecimal balance, @Param("version") Long version);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id, biz_no, biz_type, asset, direction, amount, balance_after, status, remark,
               created_at, updated_at, is_deleted)
            VALUES
              (#{userId}, #{bizNo}, 'CHARGEBACK_RECOVERY', 'USDT', 'OUT', #{amount}, #{balanceAfter},
               'SUCCESS', #{remark}, NOW(), NOW(), 0)
            """)
    int insertWalletLedger(@Param("userId") Long userId, @Param("bizNo") String bizNo,
                           @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter,
                           @Param("remark") String remark);

    @Insert("""
            INSERT INTO nx_topup_fee_buffer_ledger
              (entry_no, biz_no, direction, amount_usdt, balance_after_usdt, reason, operator,
               idempotency_key, created_at, updated_at, is_deleted)
            VALUES
              (#{entryNo}, #{bizNo}, 'OUT', #{amount}, #{balanceAfter}, #{reason}, #{operator},
               #{idempotencyKey}, NOW(), NOW(), 0)
            """)
    int insertFeeBufferLedger(@Param("entryNo") String entryNo, @Param("bizNo") String bizNo,
                              @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter,
                              @Param("reason") String reason, @Param("operator") String operator,
                              @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT reason
              FROM nx_topup_risk_lock
             WHERE target_type=#{targetType} AND target_value=#{targetValue}
               AND is_deleted=0 AND status='ACTIVE' AND locked_until > NOW()
             LIMIT 1
            """)
    String activeRiskLockReason(@Param("targetType") String targetType,
                                @Param("targetValue") String targetValue);

    @Select("""
            SELECT target_type AS targetType, target_value AS targetValue, status, source, reason,
                   locked_until AS lockedUntil,
                   CASE WHEN status='ACTIVE' AND locked_until > NOW() THEN TRUE ELSE FALSE END AS active
              FROM nx_topup_risk_lock
             WHERE target_type=#{targetType} AND target_value=#{targetValue} AND is_deleted=0
             LIMIT 1
             FOR UPDATE
            """)
    TopupRiskLockSnapshot selectRiskLockForUpdate(@Param("targetType") String targetType,
                                                  @Param("targetValue") String targetValue);

    @Insert("""
            INSERT IGNORE INTO nx_topup_card_admission
              (admission_event_id, request_hash, order_no, user_id, card_bin, client_ip,
               device_fingerprint, provider, amount_usdt, fee_amount_usdt, fee_rate_pct,
               three_ds_status, decision, reason, expires_at, created_at, updated_at, is_deleted)
            VALUES
              (#{eventId}, #{requestHash}, #{orderNo}, #{userId}, #{cardBin}, #{clientIp},
               #{deviceFingerprint}, #{provider}, #{amount}, #{feeAmount}, #{feeRate},
               #{threeDsStatus}, #{decision}, #{reason}, #{expiresAt}, NOW(), NOW(), 0)
            """)
    int insertAdmissionReceipt(@Param("eventId") String eventId,
                               @Param("requestHash") String requestHash,
                               @Param("orderNo") String orderNo,
                               @Param("userId") Long userId,
                               @Param("provider") String provider,
                               @Param("amount") BigDecimal amount,
                               @Param("feeAmount") BigDecimal feeAmount,
                               @Param("feeRate") BigDecimal feeRate,
                               @Param("threeDsStatus") String threeDsStatus,
                               @Param("cardBin") String cardBin,
                               @Param("clientIp") String clientIp,
                               @Param("deviceFingerprint") String deviceFingerprint,
                               @Param("decision") String decision,
                               @Param("reason") String reason,
                               @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT admission_event_id AS admissionEventId, request_hash AS requestHash,
                   order_no AS orderNo, user_id AS userId, provider,
                   amount_usdt AS amountUsdt, fee_amount_usdt AS feeAmountUsdt,
                   fee_rate_pct AS feeRatePct, three_ds_status AS threeDsStatus,
                   card_bin AS cardBin, client_ip AS clientIp, device_fingerprint AS deviceFingerprint,
                   decision, reason, expires_at AS expiresAt,
                   settlement_event_id AS settlementEventId, failure_event_id AS failureEventId
              FROM nx_topup_card_admission
             WHERE is_deleted=0 AND (admission_event_id=#{eventId} OR order_no=#{orderNo})
             ORDER BY admission_event_id=#{eventId} DESC LIMIT 1
             FOR UPDATE
            """)
    TopupAdmissionReceipt selectAdmissionForUpdate(@Param("eventId") String eventId,
                                                   @Param("orderNo") String orderNo);

    @Update("""
            UPDATE nx_topup_card_admission
               SET settlement_event_id=#{settlementEventId}, updated_at=NOW()
             WHERE admission_event_id=#{admissionEventId} AND is_deleted=0
               AND decision='ALLOWED' AND expires_at > NOW()
               AND failure_event_id IS NULL
               AND (settlement_event_id IS NULL OR settlement_event_id=#{settlementEventId})
            """)
    int consumeAdmission(@Param("admissionEventId") String admissionEventId,
                         @Param("settlementEventId") String settlementEventId);

    @Insert("""
            INSERT IGNORE INTO nx_topup_card_failure_event
              (failure_event_id, request_hash, admission_event_id, payment_no, order_no, user_id,
               provider, provider_payment_id, failure_status, failure_reason, occurred_at,
               created_at, updated_at, is_deleted)
            VALUES
              (#{eventId}, #{requestHash}, #{admissionEventId}, #{paymentNo}, #{orderNo}, #{userId},
               #{provider}, #{providerPaymentId}, #{status}, #{reason}, #{occurredAt}, NOW(), NOW(), 0)
            """)
    int insertFailureReceipt(@Param("eventId") String eventId,
                             @Param("requestHash") String requestHash,
                             @Param("admissionEventId") String admissionEventId,
                             @Param("paymentNo") String paymentNo,
                             @Param("orderNo") String orderNo,
                             @Param("userId") Long userId,
                             @Param("provider") String provider,
                             @Param("providerPaymentId") String providerPaymentId,
                             @Param("status") String status,
                             @Param("reason") String reason,
                             @Param("occurredAt") LocalDateTime occurredAt);

    @Select("""
            SELECT failure_event_id AS failureEventId, request_hash AS requestHash,
                   payment_no AS paymentNo, failure_status AS status
              FROM nx_topup_card_failure_event
             WHERE is_deleted=0 AND (failure_event_id=#{eventId} OR payment_no=#{paymentNo}
                OR order_no=#{orderNo} OR (provider=#{provider} AND provider_payment_id=#{providerPaymentId}))
             ORDER BY failure_event_id=#{eventId} DESC
             LIMIT 1
             FOR UPDATE
            """)
    TopupFailureReceipt selectFailureForUpdate(@Param("eventId") String eventId,
                                               @Param("paymentNo") String paymentNo,
                                               @Param("orderNo") String orderNo,
                                               @Param("provider") String provider,
                                               @Param("providerPaymentId") String providerPaymentId);

    @Update("""
            UPDATE nx_topup_card_admission
               SET failure_event_id=#{failureEventId}, updated_at=NOW()
             WHERE admission_event_id=#{admissionEventId} AND is_deleted=0 AND decision='ALLOWED'
               AND settlement_event_id IS NULL
               AND (failure_event_id IS NULL OR failure_event_id=#{failureEventId})
            """)
    int failAdmission(@Param("admissionEventId") String admissionEventId,
                      @Param("failureEventId") String failureEventId);

    @Insert("""
            INSERT INTO nx_payment_record
              (payment_no, order_no, user_id, provider, provider_payment_id, amount_usdt, currency,
               payment_status, callback_event_id, signature_status, card_bin, client_ip,
               device_fingerprint, fee_amount_usdt, fee_rate_pct, failed_at, failure_reason,
               created_at, updated_at, is_deleted)
            SELECT #{paymentNo}, #{orderNo}, #{userId}, #{provider}, #{providerPaymentId},
                   a.amount_usdt, 'USDT', #{status}, #{eventId}, 'VERIFIED', a.card_bin, a.client_ip,
                   a.device_fingerprint, a.fee_amount_usdt, a.fee_rate_pct, #{occurredAt}, #{reason},
                   NOW(), NOW(), 0
              FROM nx_topup_card_admission a
             WHERE a.admission_event_id=#{admissionEventId} AND a.is_deleted=0
            """)
    int insertFailedPayment(@Param("eventId") String eventId,
                            @Param("admissionEventId") String admissionEventId,
                            @Param("paymentNo") String paymentNo,
                            @Param("orderNo") String orderNo,
                            @Param("userId") Long userId,
                            @Param("provider") String provider,
                            @Param("providerPaymentId") String providerPaymentId,
                            @Param("status") String status,
                            @Param("reason") String reason,
                            @Param("occurredAt") LocalDateTime occurredAt);

    @Select("""
            SELECT payment_no AS paymentNo, order_no AS orderNo, user_id AS userId,
                   provider, provider_payment_id AS providerPaymentId,
                   amount_usdt AS amountUsdt, payment_status AS status,
                   (SELECT MAX(e.occurred_at) FROM nx_topup_card_chargeback_event e
                     WHERE e.is_deleted=0 AND e.payment_no=nx_payment_record.payment_no)
                     AS latestChargebackOccurredAt
              FROM nx_payment_record
             WHERE is_deleted=0 AND payment_no=#{paymentNo} AND provider=#{provider}
               AND provider_payment_id=#{providerPaymentId}
             LIMIT 1
             FOR UPDATE
            """)
    TopupChargebackSource selectChargebackSourceForUpdate(
            @Param("paymentNo") String paymentNo,
            @Param("provider") String provider,
            @Param("providerPaymentId") String providerPaymentId);

    @Insert("""
            INSERT IGNORE INTO nx_topup_card_chargeback_event
              (chargeback_event_id, request_hash, payment_no, order_no, user_id, provider,
               provider_payment_id, amount_usdt, chargeback_status, chargeback_reason,
               evidence_ref, occurred_at, created_at, updated_at, is_deleted)
            VALUES
              (#{eventId}, #{requestHash}, #{paymentNo}, #{orderNo}, #{userId}, #{provider},
               #{providerPaymentId}, #{amount}, #{status}, #{reason}, #{evidenceRef},
               #{occurredAt}, NOW(), NOW(), 0)
            """)
    int insertChargebackEvent(@Param("eventId") String eventId,
                              @Param("requestHash") String requestHash,
                              @Param("paymentNo") String paymentNo,
                              @Param("orderNo") String orderNo,
                              @Param("userId") Long userId,
                              @Param("provider") String provider,
                              @Param("providerPaymentId") String providerPaymentId,
                              @Param("amount") BigDecimal amount,
                              @Param("status") String status,
                              @Param("reason") String reason,
                              @Param("evidenceRef") String evidenceRef,
                              @Param("occurredAt") LocalDateTime occurredAt);

    @Select("""
            SELECT chargeback_event_id AS chargebackEventId, request_hash AS requestHash,
                   payment_no AS paymentNo, chargeback_status AS status
              FROM nx_topup_card_chargeback_event
             WHERE is_deleted=0 AND chargeback_event_id=#{eventId}
             LIMIT 1
             FOR UPDATE
            """)
    TopupChargebackEventReceipt selectChargebackEventForUpdate(@Param("eventId") String eventId);

    @Update("""
            UPDATE nx_payment_record
               SET payment_status=#{status}, callback_event_id=#{eventId},
                   failure_reason=#{reason}, updated_at=NOW()
             WHERE is_deleted=0 AND payment_no=#{paymentNo} AND provider=#{provider}
               AND provider_payment_id=#{providerPaymentId}
               AND payment_status=#{expectedStatus}
            """)
    int applyChargebackStatus(@Param("eventId") String eventId,
                              @Param("paymentNo") String paymentNo,
                              @Param("provider") String provider,
                              @Param("providerPaymentId") String providerPaymentId,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("status") String status,
                              @Param("reason") String reason);

    @Insert("""
            INSERT IGNORE INTO nx_topup_card_settlement
              (settlement_event_id, request_hash, admission_event_id, payment_no, order_no, user_id,
               provider, provider_payment_id, amount_usdt, fee_amount_usdt, fee_rate_pct,
               status, created_at, updated_at, is_deleted)
            VALUES
              (#{eventId}, #{requestHash}, #{admissionEventId}, #{paymentNo}, #{orderNo}, #{userId},
               #{provider}, #{providerPaymentId}, #{amount}, #{feeAmount}, #{feeRate},
               'PROCESSING', NOW(), NOW(), 0)
            """)
    int insertSettlementReceipt(@Param("eventId") String eventId,
                                @Param("requestHash") String requestHash,
                                @Param("admissionEventId") String admissionEventId,
                                @Param("paymentNo") String paymentNo,
                                @Param("orderNo") String orderNo,
                                @Param("userId") Long userId,
                                @Param("provider") String provider,
                                @Param("providerPaymentId") String providerPaymentId,
                                @Param("amount") BigDecimal amount,
                                @Param("feeAmount") BigDecimal feeAmount,
                                @Param("feeRate") BigDecimal feeRate);

    @Select("""
            SELECT settlement_event_id AS settlementEventId, request_hash AS requestHash,
                   payment_no AS paymentNo, status,
                   wallet_balance_after AS walletBalanceAfter,
                   cumulative_deposit_after AS cumulativeDepositAfter,
                   fee_buffer_balance_after AS feeBufferBalanceAfter
              FROM nx_topup_card_settlement
             WHERE settlement_event_id=#{eventId} AND is_deleted=0
             LIMIT 1
             FOR UPDATE
            """)
    TopupSettlementReceipt selectSettlementForUpdate(@Param("eventId") String eventId);

    @Update("""
            UPDATE nx_topup_card_settlement
               SET status='SETTLED', wallet_balance_after=#{walletBalanceAfter},
                   cumulative_deposit_after=#{cumulativeAfter},
                   fee_buffer_balance_after=#{feeBufferAfter}, updated_at=NOW()
             WHERE settlement_event_id=#{eventId} AND status='PROCESSING' AND is_deleted=0
            """)
    int completeSettlement(@Param("eventId") String eventId,
                           @Param("walletBalanceAfter") BigDecimal walletBalanceAfter,
                           @Param("cumulativeAfter") BigDecimal cumulativeAfter,
                           @Param("feeBufferAfter") BigDecimal feeBufferAfter);

    @Insert("""
            INSERT INTO nx_payment_record
              (payment_no, order_no, user_id, provider, provider_payment_id, amount_usdt, currency,
               payment_status, callback_event_id, signature_status, card_bin, client_ip,
               device_fingerprint, fee_amount_usdt, fee_rate_pct, paid_at,
               created_at, updated_at, is_deleted)
            SELECT #{paymentNo}, #{orderNo}, #{userId}, #{provider}, #{providerPaymentId}, #{amount}, 'USDT',
                   'CONFIRMED', #{eventId}, 'VERIFIED', a.card_bin, a.client_ip,
                   a.device_fingerprint, #{feeAmount}, #{feeRate}, #{occurredAt}, NOW(), NOW(), 0
              FROM nx_topup_card_admission a
             WHERE a.admission_event_id=#{admissionEventId} AND a.is_deleted=0
            """)
    int insertSettledPayment(@Param("eventId") String eventId,
                             @Param("admissionEventId") String admissionEventId,
                             @Param("paymentNo") String paymentNo,
                             @Param("orderNo") String orderNo,
                             @Param("userId") Long userId,
                             @Param("provider") String provider,
                             @Param("providerPaymentId") String providerPaymentId,
                             @Param("amount") BigDecimal amount,
                             @Param("feeAmount") BigDecimal feeAmount,
                             @Param("feeRate") BigDecimal feeRate,
                             @Param("occurredAt") LocalDateTime occurredAt);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id, biz_no, biz_type, asset, direction, amount, balance_after, status, remark,
               created_at, updated_at, is_deleted)
            VALUES
              (#{userId}, #{paymentNo}, 'CARD_TOPUP', 'USDT', 'IN', #{amount}, #{balanceAfter},
               'SUCCESS', #{remark}, NOW(), NOW(), 0)
            """)
    int insertCardTopupWalletLedger(@Param("userId") Long userId,
                                    @Param("paymentNo") String paymentNo,
                                    @Param("amount") BigDecimal amount,
                                    @Param("balanceAfter") BigDecimal balanceAfter,
                                    @Param("remark") String remark);

    @Update("""
            UPDATE nx_payment_record p
               SET p.wallet_ledger_id = (
                     SELECT l.id FROM nx_wallet_ledger l
                      WHERE l.user_id=p.user_id AND l.biz_no=p.payment_no
                        AND l.biz_type='CARD_TOPUP' AND l.asset='USDT' AND l.direction='IN'
                        AND l.amount=p.amount_usdt AND l.status='SUCCESS' AND l.is_deleted=0
                      LIMIT 1
                   ), p.updated_at=NOW()
             WHERE p.payment_no=#{paymentNo} AND p.is_deleted=0 AND p.wallet_ledger_id IS NULL
            """)
    int bindPaymentWalletLedger(@Param("paymentNo") String paymentNo);

    @Insert("""
            INSERT INTO nx_topup_fee_buffer_ledger
              (entry_no, biz_no, direction, amount_usdt, balance_after_usdt, reason, operator,
               idempotency_key, created_at, updated_at, is_deleted)
            VALUES
              (#{entryNo}, #{bizNo}, 'IN', #{amount}, #{balanceAfter}, #{reason}, #{operator},
               #{idempotencyKey}, NOW(), NOW(), 0)
            """)
    int insertFeeBufferCredit(@Param("entryNo") String entryNo,
                              @Param("bizNo") String bizNo,
                              @Param("amount") BigDecimal amount,
                              @Param("balanceAfter") BigDecimal balanceAfter,
                              @Param("reason") String reason,
                              @Param("operator") String operator,
                              @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT IGNORE INTO nx_topup_provider_statement
              (ingestion_event_id, payload_hash, statement_no, provider, channel_code,
               provider_reference, user_id, amount_usdt, statement_status, evidence_ref,
               observed_at, created_at, updated_at, is_deleted)
            VALUES
              (#{eventId}, #{payloadHash}, #{statementNo}, #{provider}, #{channelCode},
               #{providerReference}, #{userId}, #{amount}, #{status}, #{evidenceRef},
               #{observedAt}, NOW(), NOW(), 0)
            """)
    int insertProviderStatement(@Param("eventId") String eventId,
                                @Param("payloadHash") String payloadHash,
                                @Param("statementNo") String statementNo,
                                @Param("provider") String provider,
                                @Param("channelCode") String channelCode,
                                @Param("providerReference") String providerReference,
                                @Param("userId") Long userId,
                                @Param("amount") BigDecimal amount,
                                @Param("status") String status,
                                @Param("evidenceRef") String evidenceRef,
                                @Param("observedAt") LocalDateTime observedAt);

    @Select("""
            SELECT ingestion_event_id AS ingestionEventId, statement_no AS statementNo,
                   provider, provider_reference AS providerReference,
                   payload_hash AS payloadHash, statement_status AS statementStatus
              FROM nx_topup_provider_statement
             WHERE is_deleted=0 AND (ingestion_event_id=#{eventId} OR statement_no=#{statementNo}
                OR (provider=#{provider} AND provider_reference=#{providerReference}))
             ORDER BY ingestion_event_id=#{eventId} DESC
             LIMIT 1
             FOR UPDATE
            """)
    TopupProviderStatementReceipt selectProviderStatementForUpdate(@Param("eventId") String eventId,
                                                                   @Param("statementNo") String statementNo,
                                                                   @Param("provider") String provider,
                                                                   @Param("providerReference") String providerReference);

    @Update("""
            UPDATE nx_payment_record
               SET payment_status = #{status},
                   failure_reason = LEFT(CONCAT(COALESCE(failure_reason, ''), ' | recovery: ', #{reason}), 255),
                   updated_at = NOW()
             WHERE is_deleted = 0 AND payment_no = #{caseNo}
               AND payment_status IN ('CHARGEBACK', 'DISPUTED', 'CHARGEBACK_REVIEW', 'CHARGEBACK_REFUNDED')
            """)
    int updateChargebackStatus(@Param("caseNo") String caseNo, @Param("status") String status,
                               @Param("reason") String reason);

    @Update("""
            UPDATE nx_topup_cumulative_backfill_anomaly
               SET status='RESOLVED', updated_at=NOW()
             WHERE is_deleted=0 AND status='OPEN' AND source_type='PAYMENT'
               AND source_no=#{caseNo} AND reason_code='LEGACY_STATUS_ONLY_CHARGEBACK'
            """)
    int resolveLegacyStatusOnlyChargeback(@Param("caseNo") String caseNo);

    @Insert("""
            INSERT INTO nx_topup_chargeback_recovery
              (recovery_no, case_no, user_id, amount_usdt, recovered_usdt, wallet_shortfall_usdt,
               fee_buffer_required_usdt, fee_buffer_deducted_usdt, fee_buffer_shortfall_usdt,
               cumulative_before_usdt, cumulative_after_usdt, status, evidence_ref, reason, operator,
               idempotency_key, ledger_biz_no, risk_signal_no, created_at, updated_at, is_deleted)
            VALUES
              (#{recoveryNo}, #{caseNo}, #{userId}, #{amount}, #{recovered}, #{walletShortfall},
               #{feeRequired}, #{feeDeducted}, #{feeShortfall}, #{cumulativeBefore}, #{cumulativeAfter},
               #{status}, #{evidenceRef}, #{reason}, #{operator}, #{idempotencyKey}, #{ledgerBizNo},
               #{riskSignalNo}, NOW(), NOW(), 0)
            """)
    int insertRecovery(@Param("recoveryNo") String recoveryNo, @Param("caseNo") String caseNo,
                       @Param("userId") Long userId, @Param("amount") BigDecimal amount,
                       @Param("recovered") BigDecimal recovered, @Param("walletShortfall") BigDecimal walletShortfall,
                       @Param("feeRequired") BigDecimal feeRequired, @Param("feeDeducted") BigDecimal feeDeducted,
                       @Param("feeShortfall") BigDecimal feeShortfall,
                       @Param("cumulativeBefore") BigDecimal cumulativeBefore,
                       @Param("cumulativeAfter") BigDecimal cumulativeAfter,
                       @Param("status") String status, @Param("evidenceRef") String evidenceRef,
                       @Param("reason") String reason, @Param("operator") String operator,
                       @Param("idempotencyKey") String idempotencyKey, @Param("ledgerBizNo") String ledgerBizNo,
                       @Param("riskSignalNo") String riskSignalNo);

    @Insert("""
            INSERT INTO nx_risk_signal
              (signal_no, user_id, signal_type, severity, evidence, created_by,
               created_at, updated_at, is_deleted)
            VALUES
              (#{signalNo}, #{userId}, 'D1_CHARGEBACK_SHORTFALL', 'HIGH', #{evidence}, #{operator},
               NOW(), NOW(), 0)
            """)
    int insertRiskSignal(@Param("signalNo") String signalNo, @Param("userId") Long userId,
                         @Param("evidence") String evidence, @Param("operator") String operator);

    @Select("""
            SELECT target_type AS targetType, target_value AS targetValue, status, source, reason,
                   locked_until AS lockedUntil, TRUE AS active
              FROM nx_topup_risk_lock
             WHERE is_deleted=0 AND status='ACTIVE' AND locked_until > NOW()
             ORDER BY target_type, target_value
             FOR UPDATE
            """)
    List<TopupRiskLockSnapshot> activeRiskLockSnapshotsForUpdate();

    @Insert("""
            INSERT INTO nx_topup_risk_lock
              (target_type, target_value, status, source, reason, locked_until, created_by,
               created_at, updated_at, is_deleted)
            SELECT 'BIN', card_bin,
                   IF(DATE_ADD(MAX(created_at), INTERVAL #{lockHours} HOUR) > NOW(), 'ACTIVE', 'EXPIRED'),
                   'AUTO', CONCAT('24h failed attempts=', COUNT(1)),
                   DATE_ADD(MAX(created_at), INTERVAL #{lockHours} HOUR), 'd1-risk-scheduler', NOW(), NOW(), 0
              FROM nx_payment_record
             WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
               AND payment_status IN ('FAILED', 'DECLINED', 'EXPIRED')
               AND card_bin IS NOT NULL AND card_bin <> ''
             GROUP BY card_bin HAVING COUNT(1) >= #{threshold}
            ON DUPLICATE KEY UPDATE
              source=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), source, 'AUTO'),
              status=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), status, VALUES(status)),
              reason=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), reason, VALUES(reason)),
              locked_until=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), locked_until, VALUES(locked_until)),
              created_by=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), created_by, VALUES(created_by)),
              updated_at=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), updated_at,
                            IF(locked_until=VALUES(locked_until) AND reason=VALUES(reason), updated_at, NOW())),
              is_deleted=0
            """)
    int upsertAutoBinLocks(@Param("threshold") int threshold, @Param("lockHours") int lockHours);

    @Insert("""
            INSERT INTO nx_topup_risk_lock
              (target_type, target_value, status, source, reason, locked_until, created_by,
               created_at, updated_at, is_deleted)
            SELECT 'IP', client_ip,
                   IF(DATE_ADD(MAX(created_at), INTERVAL #{lockHours} HOUR) > NOW(), 'ACTIVE', 'EXPIRED'),
                   'AUTO', CONCAT('24h failed attempts=', COUNT(1)),
                   DATE_ADD(MAX(created_at), INTERVAL #{lockHours} HOUR), 'd1-risk-scheduler', NOW(), NOW(), 0
              FROM nx_payment_record
             WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
               AND payment_status IN ('FAILED', 'DECLINED', 'EXPIRED')
               AND client_ip IS NOT NULL AND client_ip <> ''
             GROUP BY client_ip HAVING COUNT(1) >= #{threshold}
            ON DUPLICATE KEY UPDATE
              source=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), source, 'AUTO'),
              status=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), status, VALUES(status)),
              reason=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), reason, VALUES(reason)),
              locked_until=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), locked_until, VALUES(locked_until)),
              created_by=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), created_by, VALUES(created_by)),
              updated_at=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), updated_at,
                            IF(locked_until=VALUES(locked_until) AND reason=VALUES(reason), updated_at, NOW())),
              is_deleted=0
            """)
    int upsertAutoIpLocks(@Param("threshold") int threshold, @Param("lockHours") int lockHours);

    @Insert("""
            INSERT INTO nx_topup_risk_lock
              (target_type, target_value, status, source, reason, locked_until, created_by,
               created_at, updated_at, is_deleted)
            SELECT 'DEVICE', device_fingerprint,
                   IF(DATE_ADD(MAX(created_at), INTERVAL #{lockHours} HOUR) > NOW(), 'ACTIVE', 'EXPIRED'),
                   'AUTO', CONCAT('24h failed attempts=', COUNT(1)),
                   DATE_ADD(MAX(created_at), INTERVAL #{lockHours} HOUR), 'd1-risk-scheduler', NOW(), NOW(), 0
              FROM nx_payment_record
             WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
               AND payment_status IN ('FAILED', 'DECLINED', 'EXPIRED')
               AND device_fingerprint IS NOT NULL AND device_fingerprint <> ''
             GROUP BY device_fingerprint HAVING COUNT(1) >= #{threshold}
            ON DUPLICATE KEY UPDATE
              source=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), source, 'AUTO'),
              status=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), status, VALUES(status)),
              reason=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), reason, VALUES(reason)),
              locked_until=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), locked_until, VALUES(locked_until)),
              created_by=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), created_by, VALUES(created_by)),
              updated_at=IF(source='MANUAL' AND status='ACTIVE' AND locked_until > NOW(), updated_at,
                            IF(locked_until=VALUES(locked_until) AND reason=VALUES(reason), updated_at, NOW())),
              is_deleted=0
            """)
    int upsertAutoDeviceLocks(@Param("threshold") int threshold, @Param("lockHours") int lockHours);

    @Insert("""
            INSERT INTO nx_topup_risk_lock
              (target_type, target_value, status, source, reason, locked_until, created_by,
               created_at, updated_at, is_deleted)
            VALUES
              (#{targetType}, #{targetValue}, 'ACTIVE', 'MANUAL', #{reason},
               DATE_ADD(NOW(), INTERVAL #{lockHours} HOUR), #{operator}, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE status='ACTIVE', source='MANUAL', reason=VALUES(reason),
              locked_until=VALUES(locked_until), created_by=VALUES(created_by), updated_at=NOW(), is_deleted=0
            """)
    int activateManualRiskLock(@Param("targetType") String targetType, @Param("targetValue") String targetValue,
                               @Param("lockHours") int lockHours, @Param("reason") String reason,
                               @Param("operator") String operator);

    @Update("""
            UPDATE nx_topup_risk_lock
               SET status='RELEASED', reason=#{reason}, created_by=#{operator},
                   locked_until=NOW(), updated_at=NOW()
             WHERE target_type=#{targetType} AND target_value=#{targetValue} AND is_deleted=0
            """)
    int releaseRiskLock(@Param("targetType") String targetType, @Param("targetValue") String targetValue,
                        @Param("reason") String reason, @Param("operator") String operator);
}
