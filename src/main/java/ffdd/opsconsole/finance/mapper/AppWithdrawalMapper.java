package ffdd.opsconsole.finance.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** User withdrawal command-side persistence; every money mutation is executed in one transaction. */
@Mapper
// Statement-only command boundary spanning users, wallets, orders, ledgers and outbox rows.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppWithdrawalMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_withdrawal_attempt_control (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              user_id BIGINT NOT NULL,
              idempotency_key VARCHAR(128) NOT NULL,
              request_hash CHAR(64) NOT NULL,
              status VARCHAR(16) NOT NULL,
              withdrawal_no VARCHAR(96) NULL,
              created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
              updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
              UNIQUE KEY uk_withdrawal_attempt_user_key (user_id,idempotency_key),
              KEY idx_withdrawal_attempt_status (status,updated_at),
              CONSTRAINT chk_withdrawal_attempt_status CHECK (status IN ('ACTIVE','COMMITTED','ABANDONED'))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createWithdrawalAttemptTable();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.TABLE_CONSTRAINTS
             WHERE CONSTRAINT_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_withdrawal_attempt_control'
               AND CONSTRAINT_NAME='chk_withdrawal_attempt_status'
               AND CONSTRAINT_TYPE='CHECK'
            """)
    int withdrawalAttemptStatusCheckCount();

    @Insert("""
            INSERT IGNORE INTO nx_withdrawal_attempt_control
              (user_id,idempotency_key,request_hash,status,created_at,updated_at)
            VALUES (#{userId},#{idempotencyKey},#{requestHash},#{status},CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """)
    int insertWithdrawalAttempt(@Param("userId") Long userId,
                                @Param("idempotencyKey") String idempotencyKey,
                                @Param("requestHash") String requestHash,
                                @Param("status") String status);

    @Select("""
            SELECT user_id AS userId,idempotency_key AS idempotencyKey,request_hash AS requestHash,
                   status,withdrawal_no AS withdrawalNo
              FROM nx_withdrawal_attempt_control
             WHERE user_id=#{userId} AND idempotency_key=#{idempotencyKey}
             LIMIT 1 FOR UPDATE
            """)
    WithdrawalAttemptRow lockWithdrawalAttempt(@Param("userId") Long userId,
                                               @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT withdrawal_no
              FROM nx_withdrawal_order
             WHERE user_id=#{userId} AND d2_idempotency_key=#{idempotencyKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    String findWithdrawalNoByIdempotencyKey(@Param("userId") Long userId,
                                           @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE nx_withdrawal_attempt_control
               SET status='ABANDONED',updated_at=CURRENT_TIMESTAMP(6)
             WHERE user_id=#{userId} AND idempotency_key=#{idempotencyKey} AND status='ACTIVE'
            """)
    int abandonWithdrawalAttempt(@Param("userId") Long userId,
                                 @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE nx_withdrawal_attempt_control
               SET status='COMMITTED',withdrawal_no=#{withdrawalNo},updated_at=CURRENT_TIMESTAMP(6)
             WHERE user_id=#{userId} AND idempotency_key=#{idempotencyKey}
               AND ((status IN ('ACTIVE','ABANDONED'))
                    OR (status='COMMITTED' AND withdrawal_no=#{withdrawalNo}))
               AND (withdrawal_no IS NULL OR withdrawal_no=#{withdrawalNo})
            """)
    int commitWithdrawalAttempt(@Param("userId") Long userId,
                                @Param("idempotencyKey") String idempotencyKey,
                                @Param("withdrawalNo") String withdrawalNo);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    /** A production withdrawal may never proceed for an acceptance fixture user. */
    @Select("SELECT 1 FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 AND COALESCE(sandbox,0)=1 LIMIT 1")
    Integer isSandboxUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Long findActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT setting_value
              FROM nx_emergency_control_setting
             WHERE setting_key=#{settingKey} AND is_deleted=0
             LIMIT 1
            """)
    String emergencyValue(@Param("settingKey") String settingKey);

    @Select("""
            SELECT w.user_id userId,w.usdt_available usdtAvailable,w.nex_available nexAvailable,
                   w.pending_withdraw pendingWithdraw,w.version
              FROM nx_user_wallet w
             WHERE w.user_id=#{userId} AND w.is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    WalletRow lockWallet(@Param("userId") Long userId);

    @Select("""
            SELECT w.user_id userId,w.usdt_available usdtAvailable,w.nex_available nexAvailable,
                   w.pending_withdraw pendingWithdraw,w.version
              FROM nx_user_wallet w
             WHERE w.user_id=#{userId} AND w.is_deleted=0 LIMIT 1
            """)
    WalletRow walletForEligibility(@Param("userId") Long userId);

    @Select("""
            SELECT network,address,effective_at effectiveAt,next_change_allowed_at nextChangeAllowedAt
              FROM nx_user_payout_address
             WHERE user_id=#{userId} AND network=#{network} AND status='ACTIVE' AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    PayoutAddressRow lockPayoutAddress(@Param("userId") Long userId, @Param("network") String network);

    @Select("""
            SELECT network,address,effective_at effectiveAt,next_change_allowed_at nextChangeAllowedAt
              FROM nx_user_payout_address
             WHERE user_id=#{userId} AND network=#{network} AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    PayoutAddressRow payoutAddressForEligibility(
            @Param("userId") Long userId,
            @Param("network") String network);

    @Select("""
            SELECT COUNT(1) FROM nx_withdrawal_order
             WHERE user_id=#{userId} AND created_at>=#{fromInclusive}
               AND created_at<#{toExclusive} AND is_deleted=0
            """)
    int countBusinessDay(@Param("userId") Long userId,
                         @Param("fromInclusive") LocalDateTime fromInclusive,
                         @Param("toExclusive") LocalDateTime toExclusive);

    @Select("""
            SELECT CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')) userNo,
                   (SELECT COUNT(1) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.created_at>=DATE_SUB(NOW(),INTERVAL 24 HOUR)
                       AND w.is_deleted=0) withdrawalCount24h,
                   (SELECT COALESCE(SUM(w.amount),0) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.created_at>=DATE_SUB(NOW(),INTERVAL 24 HOUR)
                       AND w.is_deleted=0) withdrawalSum24h,
                   GREATEST(TIMESTAMPDIFF(DAY,u.created_at,NOW()),0) accountAgeDays,
                   CASE WHEN EXISTS(
                       SELECT 1 FROM nx_risk_signal s
                        WHERE (s.user_id=u.id
                               OR UPPER(COALESCE(s.evidence,'')) LIKE CONCAT('%',UPPER(SHA2(#{targetAddress},256)),'%'))
                          AND s.is_deleted=0
                          AND UPPER(s.signal_type) IN ('ADDRESS_BLACKLIST','ADDRESS_REPUTATION_LOW')
                          AND UPPER(s.severity) IN ('HIGH','CRITICAL')
                   ) OR EXISTS(
                       SELECT 1 FROM nx_risk_decision rd
                        WHERE rd.user_id=u.id AND rd.is_deleted=0
                          AND (UPPER(COALESCE(rd.rule_codes,'')) REGEXP 'ADDRESS_(BLACKLIST|REPUTATION_LOW)'
                            OR UPPER(COALESCE(rd.reason,'')) REGEXP 'ADDRESS_(BLACKLIST|REPUTATION_LOW)')
                   ) THEN 'low' ELSE 'normal' END addressReputation,
                   COALESCE(k4o.override_score,k4.model_score,
                     CASE WHEN COALESCE(u.sandbox,0)=1 AND k4.user_no IS NULL THEN 0 END) k4RiskScore,
                   COALESCE(k4.model_version,
                     CASE WHEN COALESCE(u.sandbox,0)=1 AND k4.user_no IS NULL
                          THEN CONCAT('k4-v',k4m.model_version) END) k4ModelVersion,
                   COALESCE(k4.as_of,
                     CASE WHEN COALESCE(u.sandbox,0)=1 AND k4.user_no IS NULL THEN NOW() END) k4AsOf,
                   k4m.band_low_max k4BandLowMax,
                   k4m.band_high_min k4BandHighMin,
                   k4m.auto_escalate_score k4AutoEscalateScore
              FROM nx_user u
              LEFT JOIN nx_admin_risk_score_user k4
                ON k4.user_no=CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0'))
               AND k4.is_deleted=0
               AND k4.as_of>=DATE_SUB(NOW(),INTERVAL 1 DAY)
              LEFT JOIN nx_admin_risk_score_model k4m
                ON k4m.state='active' AND k4m.is_deleted=0
               AND (k4.model_version=CONCAT('k4-v',k4m.model_version)
                    OR (COALESCE(u.sandbox,0)=1 AND k4.user_no IS NULL))
              LEFT JOIN nx_admin_risk_score_override k4o
                ON k4o.user_no=k4.user_no AND k4o.active=1 AND k4o.is_deleted=0
             WHERE u.id=#{userId} AND u.is_deleted=0
             LIMIT 1
            """)
    WithdrawalRiskFacts withdrawalRiskFacts(
            @Param("userId") Long userId,
            @Param("targetAddress") String targetAddress);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available=usdt_available-#{amount},
                   nex_available=nex_available-#{nexBurned},
                   pending_withdraw=pending_withdraw+#{amount},
                   version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND version=#{version} AND is_deleted=0
               AND usdt_available>=#{amount} AND nex_available>=#{nexBurned}
            """)
    int reserveFunds(
            @Param("userId") Long userId,
            @Param("amount") BigDecimal amount,
            @Param("nexBurned") BigDecimal nexBurned,
            @Param("version") Long version);

    @Insert("""
            INSERT INTO nx_withdrawal_order
               (user_id,withdrawal_no,asset,chain,amount,fee,target_address,status,d2_idempotency_key,
               d2_version,d2_hold_until,d5_payout_due_at,d2_penalty_fee_rate,d2_gross_fee,d2_nex_burned,
               d2_network_fee_rate,d2_network_fee_min,d2_network_fee_max,d2_network_fee,
               d2_nex_fee_offset_rate,d2_fee_waived,d2_actual_fee,d2_net_receive,
               d5_policy_version,d5_use_nex_fee_offset,
               d2_lifecycle_owner,d2_freeze_period,d2_routing_priority,d2_k3_risk_route,
               d2_k4_risk_score,d2_k4_model_version,d2_k4_as_of,
               d2_k4_band_low_max,d2_k4_band_high_min,d2_k4_auto_escalate_score,
               failure_reason,d2_previous_status,
               created_at,updated_at,is_deleted)
            VALUES
               (#{userId},#{withdrawalNo},'USDT',#{chain},#{amount},#{actualFee},#{targetAddress},#{status},#{idempotencyKey},
               0,#{holdUntil},#{payoutDueAt},#{penaltyFeeRate},#{grossFee},#{nexBurned},
               #{networkFeeRate},#{networkFeeMin},#{networkFeeMax},#{networkFee},
               #{nexFeeOffsetRate},#{feeWaived},#{actualFee},#{netReceive},
               #{policyVersion},#{useNexFeeOffset},
               #{lifecycleOwner},#{freezePeriod},#{routingPriority},#{k3RiskRoute},
               #{k4RiskScore},#{k4ModelVersion},#{k4AsOf},#{k4BandLowMax},#{k4BandHighMin},#{k4AutoEscalateScore},
               #{failureReason},#{previousStatus},NOW(),NOW(),0)
            """)
    int insertWithdrawal(WithdrawalWrite write);

    @Select("""
            SELECT w.withdrawal_no withdrawalNo,w.amount,w.fee,w.chain,w.target_address targetAddress,
                   w.status,w.d2_hold_until holdUntil,w.d2_penalty_fee_rate penaltyFeeRate,
                   w.d2_network_fee_rate networkFeeRate,w.d2_network_fee_min networkFeeMin,
                   w.d2_network_fee_max networkFeeMax,w.d2_network_fee networkFee,
                   w.d2_gross_fee grossFee,w.d2_nex_burned nexBurned,w.d2_fee_waived feeWaived,
                   w.d2_network_fee networkConfirmUsd,0 penaltyFee,
                   w.d2_actual_fee actualFee,w.d2_net_receive netReceive,w.created_at createdAt,
                   w.d5_policy_version policyVersion,w.d5_use_nex_fee_offset useNexFeeOffset,
                   CASE WHEN UPPER(COALESCE(w.failure_reason,''))='A3_STRONG_REVIEW_THRESHOLD' THEN 'manual'
                        WHEN UPPER(COALESCE(w.d2_k3_risk_route,''))='PASS' THEN 'fast-pass'
                        WHEN UPPER(COALESCE(w.d2_k3_risk_route,''))='MANUAL' THEN 'manual'
                        ELSE LOWER(COALESCE(w.d2_k3_risk_route,'manual')) END riskRoute,
                   'server' idSource,
                   CASE WHEN UPPER(w.status) IN ('REFUNDED','REVIEW_REJECTED','REJECTED','FAILED','TX_FAILED','TX_ORPHANED','DEAD','ADDRESS_INVALID')
                        THEN COALESCE(w.terminal_reason,w.failure_reason) ELSE NULL END terminalReason,
                   CASE WHEN UPPER(w.status) IN ('REFUNDED','REVIEW_REJECTED','REJECTED','FAILED','TX_FAILED','TX_ORPHANED','DEAD','ADDRESS_INVALID')
                        THEN w.retriable ELSE NULL END retriable,
                   COALESCE((SELECT SUM(l.amount) FROM nx_wallet_ledger l
                              WHERE l.user_id=w.user_id AND l.asset='NEX' AND l.direction='IN'
                                AND l.biz_type='WITHDRAW_FEE_OFFSET_REFUND'
                                AND l.biz_no=CONCAT('D2-NEX-REFUND-',w.withdrawal_no)
                                AND l.is_deleted=0),0) nexRefunded,
                   (SELECT MAX(l.created_at) FROM nx_wallet_ledger l
                     WHERE l.user_id=w.user_id AND l.asset='NEX' AND l.direction='IN'
                       AND l.biz_type='WITHDRAW_FEE_OFFSET_REFUND'
                       AND l.biz_no=CONCAT('D2-NEX-REFUND-',w.withdrawal_no)
                       AND l.is_deleted=0) nexRefundedAt
              FROM nx_withdrawal_order w
              JOIN nx_user u ON u.id=w.user_id AND u.status='ACTIVE' AND u.is_deleted=0
             WHERE w.user_id=#{userId} AND w.is_deleted=0
             ORDER BY w.created_at DESC,w.id DESC LIMIT #{offset},#{limit}
            """)
    List<Map<String, Object>> userWithdrawals(
            @Param("userId") Long userId, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_withdrawal_order w
              JOIN nx_user u ON u.id=w.user_id AND u.status='ACTIVE' AND u.is_deleted=0
             WHERE w.user_id=#{userId} AND w.is_deleted=0
            """)
    long countUserWithdrawals(@Param("userId") Long userId);

    @Select("""
            SELECT w.withdrawal_no withdrawalNo,w.amount,w.fee,w.chain,w.target_address targetAddress,
                   w.status,w.d2_hold_until holdUntil,w.d2_penalty_fee_rate penaltyFeeRate,
                   w.d2_network_fee_rate networkFeeRate,w.d2_network_fee_min networkFeeMin,
                   w.d2_network_fee_max networkFeeMax,w.d2_network_fee networkFee,
                   w.d2_network_fee networkConfirmUsd,0 penaltyFee,
                   w.d2_gross_fee grossFee,w.d2_nex_burned nexBurned,w.d2_fee_waived feeWaived,
                   w.d2_actual_fee actualFee,w.d2_net_receive netReceive,w.created_at createdAt,
                   w.d5_policy_version policyVersion,w.d5_use_nex_fee_offset useNexFeeOffset,
                   CASE WHEN UPPER(COALESCE(w.failure_reason,''))='A3_STRONG_REVIEW_THRESHOLD' THEN 'manual'
                        WHEN UPPER(COALESCE(w.d2_k3_risk_route,''))='PASS' THEN 'fast-pass'
                        WHEN UPPER(COALESCE(w.d2_k3_risk_route,''))='MANUAL' THEN 'manual'
                        ELSE LOWER(COALESCE(w.d2_k3_risk_route,'manual')) END riskRoute,
                   'server' idSource,
                   CASE WHEN UPPER(w.status) IN ('REFUNDED','REVIEW_REJECTED','REJECTED','FAILED','TX_FAILED','TX_ORPHANED','DEAD','ADDRESS_INVALID')
                        THEN COALESCE(w.terminal_reason,w.failure_reason) ELSE NULL END terminalReason,
                   CASE WHEN UPPER(w.status) IN ('REFUNDED','REVIEW_REJECTED','REJECTED','FAILED','TX_FAILED','TX_ORPHANED','DEAD','ADDRESS_INVALID')
                        THEN w.retriable ELSE NULL END retriable,
                   COALESCE((SELECT SUM(l.amount) FROM nx_wallet_ledger l
                              WHERE l.user_id=w.user_id AND l.asset='NEX' AND l.direction='IN'
                                AND l.biz_type='WITHDRAW_FEE_OFFSET_REFUND'
                                AND l.biz_no=CONCAT('D2-NEX-REFUND-',w.withdrawal_no)
                                AND l.is_deleted=0),0) nexRefunded,
                   (SELECT MAX(l.created_at) FROM nx_wallet_ledger l
                     WHERE l.user_id=w.user_id AND l.asset='NEX' AND l.direction='IN'
                       AND l.biz_type='WITHDRAW_FEE_OFFSET_REFUND'
                       AND l.biz_no=CONCAT('D2-NEX-REFUND-',w.withdrawal_no)
                       AND l.is_deleted=0) nexRefundedAt
              FROM nx_withdrawal_order w
              JOIN nx_user u ON u.id=w.user_id AND u.status='ACTIVE' AND u.is_deleted=0
             WHERE w.user_id=#{userId} AND w.withdrawal_no=#{withdrawalNo} AND w.is_deleted=0
             LIMIT 1
            """)
    Map<String, Object> userWithdrawal(@Param("userId") Long userId,
                                       @Param("withdrawalNo") String withdrawalNo);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    Attribution attribution(@Param("userId") Long userId);

    record WalletRow(Long userId, BigDecimal usdtAvailable, BigDecimal nexAvailable,
                     BigDecimal pendingWithdraw, Long version) { }

    record PayoutAddressRow(String network, String address, LocalDateTime effectiveAt,
                            LocalDateTime nextChangeAllowedAt) { }

    record WithdrawalAttemptRow(Long userId, String idempotencyKey, String requestHash,
                                String status, String withdrawalNo) { }

    record WithdrawalWrite(
            Long userId, String withdrawalNo, String chain, BigDecimal amount, String targetAddress,
            LocalDateTime holdUntil, LocalDateTime payoutDueAt,
            BigDecimal networkFeeRate, BigDecimal networkFeeMin, BigDecimal networkFeeMax, BigDecimal networkFee,
            BigDecimal penaltyFeeRate, BigDecimal grossFee,
            BigDecimal nexBurned, BigDecimal nexFeeOffsetRate, BigDecimal feeWaived,
            BigDecimal actualFee, BigDecimal netReceive, String policyVersion, Boolean useNexFeeOffset,
            String idempotencyKey,
            String lifecycleOwner, String freezePeriod,
            String routingPriority, String k3RiskRoute,
            Integer k4RiskScore, String k4ModelVersion, LocalDateTime k4AsOf,
            Integer k4BandLowMax, Integer k4BandHighMin, Integer k4AutoEscalateScore,
            String status, String failureReason, String previousStatus) { }

    record WithdrawalRiskFacts(
            String userNo,
            Integer withdrawalCount24h,
            BigDecimal withdrawalSum24h,
            Integer accountAgeDays,
            String addressReputation,
            Integer k4RiskScore,
            String k4ModelVersion,
            LocalDateTime k4AsOf,
            Integer k4BandLowMax,
            Integer k4BandHighMin,
            Integer k4AutoEscalateScore) { }

    record Attribution(String phase, Integer accountAgeMonths, String cohort) { }
}
