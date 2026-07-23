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

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Long findActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT w.user_id userId,w.usdt_available usdtAvailable,w.nex_available nexAvailable,
                   w.pending_withdraw pendingWithdraw,w.version
              FROM nx_user_wallet w
             WHERE w.user_id=#{userId} AND w.is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    WalletRow lockWallet(@Param("userId") Long userId);

    @Select("""
            SELECT status,paired_address pairedAddress,network
              FROM nx_kyc_profile
             WHERE user_id=#{userId} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    KycWalletRow lockKycWallet(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1) FROM nx_withdrawal_order
             WHERE user_id=#{userId} AND created_at>=DATE_SUB(NOW(),INTERVAL 24 HOUR) AND is_deleted=0
            """)
    int countLast24Hours(@Param("userId") Long userId);

    @Select("""
            SELECT CONCAT('U', LPAD(u.id, 8, '0')) userNo,
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
                   COALESCE(k4o.override_score,k4.model_score) k4RiskScore,
                   k4.model_version k4ModelVersion,
                   k4.as_of k4AsOf,
                   k4m.band_low_max k4BandLowMax,
                   k4m.band_high_min k4BandHighMin,
                   k4m.auto_escalate_score k4AutoEscalateScore
              FROM nx_user u
              LEFT JOIN nx_admin_risk_score_user k4
                ON k4.user_no=CONCAT('U',LPAD(u.id,8,'0'))
               AND k4.is_deleted=0
               AND k4.as_of>=DATE_SUB(NOW(),INTERVAL 1 DAY)
              LEFT JOIN nx_admin_risk_score_model k4m
                ON k4m.state='active' AND k4m.is_deleted=0
               AND k4.model_version=CONCAT('k4-v',k4m.model_version)
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
              (user_id,withdrawal_no,asset,chain,amount,fee,target_address,status,
               d2_version,d2_hold_until,d2_penalty_fee_rate,d2_gross_fee,d2_nex_burned,
               d2_nex_fee_offset_rate,d2_fee_waived,d2_actual_fee,d2_net_receive,
               d2_lifecycle_owner,d2_freeze_period,d2_routing_priority,d2_k3_risk_route,
               d2_k4_risk_score,d2_k4_model_version,d2_k4_as_of,
               d2_k4_band_low_max,d2_k4_band_high_min,d2_k4_auto_escalate_score,
               failure_reason,d2_previous_status,
               created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{withdrawalNo},'USDT',#{chain},#{amount},#{actualFee},#{targetAddress},#{status},
               0,#{holdUntil},#{penaltyFeeRate},#{grossFee},#{nexBurned},
               #{nexFeeOffsetRate},#{feeWaived},#{actualFee},#{netReceive},
               #{lifecycleOwner},#{freezePeriod},#{routingPriority},#{k3RiskRoute},
               #{k4RiskScore},#{k4ModelVersion},#{k4AsOf},#{k4BandLowMax},#{k4BandHighMin},#{k4AutoEscalateScore},
               #{failureReason},#{previousStatus},NOW(),NOW(),0)
            """)
    int insertWithdrawal(WithdrawalWrite write);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},#{asset},'OUT',#{amount},#{balanceAfter},'POSTED',#{remark},NOW(),NOW(),0)
            """)
    int insertLedger(LedgerWrite write);

    @Select("""
            SELECT w.withdrawal_no withdrawalNo,w.amount,w.fee,w.chain,w.target_address targetAddress,
                   w.status,w.d2_hold_until holdUntil,w.d2_penalty_fee_rate penaltyFeeRate,
                   w.d2_gross_fee grossFee,w.d2_nex_burned nexBurned,w.d2_fee_waived feeWaived,
                   w.d2_actual_fee actualFee,w.d2_net_receive netReceive,w.created_at createdAt
              FROM nx_withdrawal_order w
             WHERE w.user_id=#{userId} AND w.is_deleted=0
             ORDER BY w.created_at DESC,w.id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> userWithdrawals(@Param("userId") Long userId, @Param("limit") int limit);

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

    record KycWalletRow(String status, String pairedAddress, String network) { }

    record WithdrawalWrite(
            Long userId, String withdrawalNo, String chain, BigDecimal amount, String targetAddress,
            LocalDateTime holdUntil, BigDecimal penaltyFeeRate, BigDecimal grossFee,
            BigDecimal nexBurned, BigDecimal nexFeeOffsetRate, BigDecimal feeWaived,
            BigDecimal actualFee, BigDecimal netReceive, String lifecycleOwner, String freezePeriod,
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

    record LedgerWrite(
            Long userId, String bizNo, String bizType, String asset,
            BigDecimal amount, BigDecimal balanceAfter, String remark) { }

    record Attribution(String phase, Integer accountAgeMonths, String cohort) { }
}
