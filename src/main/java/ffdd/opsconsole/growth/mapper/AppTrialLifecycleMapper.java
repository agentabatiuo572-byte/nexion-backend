package ffdd.opsconsole.growth.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Row-locking persistence boundary for the server-authoritative H2 lifecycle. */
@Mapper
// Statement-only trial lifecycle boundary spanning users, claims, wallets and devices.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppTrialLifecycleMapper {

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Long activeUser(@Param("userId") Long userId);

    @Select("""
            SELECT id,user_id userId,claim_no claimNo,status,user_device_id userDeviceId,
                   payment_method_id paymentMethodId,device_name deviceName,duration_days durationDays,
                   daily_usdt dailyUsdt,daily_nex dailyNex,offset_cap_usdt offsetCapUsdt,
                   price_usdt priceUsdt,claimed_at claimedAt,expires_at expiresAt,
                   shadow_accrued_usdt shadowAccruedUsdt,shadow_accrued_nex shadowAccruedNex,
                   remainder_usdt remainderUsdt,discount_usdt discountUsdt,
                   settlement_amount_usdt settlementAmountUsdt,cooldown_until cooldownUntil,
                   version
              FROM nx_trial_claim
             WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    TrialRow lockTrial(@Param("userId") Long userId);

    @Select("""
            SELECT id,user_id userId,claim_no claimNo,status,user_device_id userDeviceId,
                   payment_method_id paymentMethodId,device_name deviceName,duration_days durationDays,
                   daily_usdt dailyUsdt,daily_nex dailyNex,offset_cap_usdt offsetCapUsdt,
                   price_usdt priceUsdt,claimed_at claimedAt,expires_at expiresAt,
                   shadow_accrued_usdt shadowAccruedUsdt,shadow_accrued_nex shadowAccruedNex,
                   remainder_usdt remainderUsdt,discount_usdt discountUsdt,
                   settlement_amount_usdt settlementAmountUsdt,cooldown_until cooldownUntil,
                   version
              FROM nx_trial_claim
             WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1
            """)
    TrialRow trial(@Param("userId") Long userId);

    @Select("""
            SELECT policy_key policyKey,current_value currentValue
              FROM nx_growth_trial_policy WHERE is_deleted=0
            """)
    List<PolicyRow> policies();

    @Select("""
            SELECT COUNT(*) FROM nx_risk_signal
             WHERE user_id=#{userId} AND signal_type='risk.trial_cycle_detected' AND is_deleted=0
            """)
    long trialCycleSignalCount(@Param("userId") Long userId);

    @Select("""
            SELECT id FROM nx_wallet_bank_card
             WHERE id=#{paymentMethodId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(status) IN ('ACTIVE','BOUND','VERIFIED')
             LIMIT 1 FOR UPDATE
            """)
    Long lockUsablePaymentMethod(
            @Param("userId") Long userId, @Param("paymentMethodId") Long paymentMethodId);

    @Insert("""
            INSERT INTO nx_trial_claim
              (user_id,claim_no,client_request_no,status,user_device_id,payment_method_id,device_name,
               duration_days,daily_usdt,daily_nex,seats_left_today,offset_cap_usdt,price_usdt,
               claimed_at,expires_at,quota_snapshot,shadow_accrued_usdt,shadow_accrued_nex,
               remainder_usdt,discount_usdt,settlement_amount_usdt,cancel_reason,extended_at,
               settled_at,closed_at,cooldown_until,settlement_snapshot,version,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{claimNo},#{idempotencyKey},'ACTIVE',NULL,#{paymentMethodId},#{deviceName},
               #{durationDays},#{dailyUsdt},#{dailyNex},0,#{offsetCapUsdt},#{priceUsdt},
               #{now},#{expiresAt},#{quotaSnapshot},0,0,0,0,0,NULL,NULL,NULL,NULL,NULL,NULL,0,NOW(),NOW(),0)
            """)
    int insertTrial(
            @Param("userId") Long userId,
            @Param("claimNo") String claimNo,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("paymentMethodId") Long paymentMethodId,
            @Param("deviceName") String deviceName,
            @Param("durationDays") int durationDays,
            @Param("dailyUsdt") BigDecimal dailyUsdt,
            @Param("dailyNex") BigDecimal dailyNex,
            @Param("offsetCapUsdt") BigDecimal offsetCapUsdt,
            @Param("priceUsdt") BigDecimal priceUsdt,
            @Param("now") LocalDateTime now,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("quotaSnapshot") String quotaSnapshot);

    @Update("""
            UPDATE nx_trial_claim
               SET claim_no=#{claimNo},client_request_no=#{idempotencyKey},status='ACTIVE',
                   user_device_id=NULL,payment_method_id=#{paymentMethodId},device_name=#{deviceName},
                   duration_days=#{durationDays},daily_usdt=#{dailyUsdt},daily_nex=#{dailyNex},
                   offset_cap_usdt=#{offsetCapUsdt},price_usdt=#{priceUsdt},claimed_at=#{now},
                   expires_at=#{expiresAt},quota_snapshot=#{quotaSnapshot},shadow_accrued_usdt=0,
                   shadow_accrued_nex=0,remainder_usdt=0,discount_usdt=0,settlement_amount_usdt=0,
                   cancel_reason=NULL,extended_at=NULL,settled_at=NULL,closed_at=NULL,cooldown_until=NULL,
                   settlement_snapshot=NULL,version=version+1,updated_at=NOW(),is_deleted=0
             WHERE id=#{id} AND version=#{version}
               AND UPPER(status) IN ('CANCELLED','FAILED')
               AND (cooldown_until IS NULL OR cooldown_until<=#{now})
            """)
    int restartTrial(
            @Param("id") Long id,
            @Param("version") long version,
            @Param("claimNo") String claimNo,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("paymentMethodId") Long paymentMethodId,
            @Param("deviceName") String deviceName,
            @Param("durationDays") int durationDays,
            @Param("dailyUsdt") BigDecimal dailyUsdt,
            @Param("dailyNex") BigDecimal dailyNex,
            @Param("offsetCapUsdt") BigDecimal offsetCapUsdt,
            @Param("priceUsdt") BigDecimal priceUsdt,
            @Param("now") LocalDateTime now,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("quotaSnapshot") String quotaSnapshot);

    @Update("""
            UPDATE nx_trial_claim
               SET status='GRACE',version=version+1,updated_at=NOW()
             WHERE id=#{id} AND version=#{version} AND UPPER(status)='ACTIVE' AND expires_at<=#{now}
            """)
    int enterGrace(@Param("id") Long id, @Param("version") long version, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_trial_claim
               SET status='CANCELLED',shadow_accrued_usdt=0,shadow_accrued_nex=0,
                   cancel_reason=#{reason},closed_at=#{now},cooldown_until=#{cooldownUntil},
                   version=version+1,updated_at=NOW()
             WHERE id=#{id} AND version=#{version}
               AND UPPER(status) IN ('ACTIVE','GRACE','EXTENDED')
            """)
    int cancelTrial(
            @Param("id") Long id,
            @Param("version") long version,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now,
            @Param("cooldownUntil") LocalDateTime cooldownUntil);

    @Update("""
            UPDATE nx_trial_claim
               SET status='EXTENDED',expires_at=DATE_ADD(expires_at,INTERVAL #{extensionDays} DAY),
                   extended_at=#{now},version=version+1,updated_at=NOW()
             WHERE id=#{id} AND version=#{version} AND UPPER(status)='GRACE'
            """)
    int extendTrial(
            @Param("id") Long id,
            @Param("version") long version,
            @Param("extensionDays") int extensionDays,
            @Param("now") LocalDateTime now);

    @Select("SELECT usdt_available usdt,nex_available nex FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    WalletRow lockWallet(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available=usdt_available-#{chargeUsdt}+#{remainderUsdt},
                   nex_available=nex_available+#{rewardNex},
                   lifetime_earned=lifetime_earned+#{remainderUsdt}+#{rewardNex},
                   version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0 AND usdt_available>=#{chargeUsdt}
            """)
    int settleWallet(
            @Param("userId") Long userId,
            @Param("chargeUsdt") BigDecimal chargeUsdt,
            @Param("remainderUsdt") BigDecimal remainderUsdt,
            @Param("rewardNex") BigDecimal rewardNex);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},#{asset},#{direction},#{amount},#{balanceAfter},'POSTED',#{remark},NOW(),NOW(),0)
            """)
    int insertLedger(
            @Param("userId") Long userId,
            @Param("bizNo") String bizNo,
            @Param("bizType") String bizType,
            @Param("asset") String asset,
            @Param("direction") String direction,
            @Param("amount") BigDecimal amount,
            @Param("balanceAfter") BigDecimal balanceAfter,
            @Param("remark") String remark);

    @Insert("""
            INSERT INTO nx_user_device
              (user_id,source_order_no,product_id,product_code,product_tier,device_id,instance_no,name,
               device_type,generation,base_power_w,price_usdt_snapshot,ownership_status,source_channel,
               status,hashrate,daily_usdt,daily_nex,purchased_at,activated_at,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{claimNo},NULL,#{productCode},'TRIAL',NULL,#{instanceNo},#{deviceName},
               'CLOUD',1,0,#{priceUsdt},'OWNED','TRIAL','ACTIVE',0,#{dailyUsdt},#{dailyNex},NOW(),NOW(),NOW(),NOW(),0)
            """)
    int insertPurchasedDevice(
            @Param("userId") Long userId,
            @Param("claimNo") String claimNo,
            @Param("productCode") String productCode,
            @Param("instanceNo") String instanceNo,
            @Param("deviceName") String deviceName,
            @Param("priceUsdt") BigDecimal priceUsdt,
            @Param("dailyUsdt") BigDecimal dailyUsdt,
            @Param("dailyNex") BigDecimal dailyNex);

    @Select("SELECT id FROM nx_user_device WHERE instance_no=#{instanceNo} AND is_deleted=0 LIMIT 1")
    Long deviceIdByInstanceNo(@Param("instanceNo") String instanceNo);

    @Update("""
            UPDATE nx_trial_claim
               SET status='REDEEMED',user_device_id=#{deviceId},shadow_accrued_usdt=#{shadowUsdt},
                   shadow_accrued_nex=#{shadowNex},remainder_usdt=#{remainderUsdt},discount_usdt=#{discountUsdt},
                   settlement_amount_usdt=#{chargeUsdt},settled_at=#{now},closed_at=#{now},
                   settlement_snapshot=#{snapshot},version=version+1,updated_at=NOW()
             WHERE id=#{id} AND version=#{version}
               AND UPPER(status) IN ('ACTIVE','GRACE','EXTENDED')
            """)
    int markRedeemed(
            @Param("id") Long id,
            @Param("version") long version,
            @Param("deviceId") Long deviceId,
            @Param("shadowUsdt") BigDecimal shadowUsdt,
            @Param("shadowNex") BigDecimal shadowNex,
            @Param("remainderUsdt") BigDecimal remainderUsdt,
            @Param("discountUsdt") BigDecimal discountUsdt,
            @Param("chargeUsdt") BigDecimal chargeUsdt,
            @Param("now") LocalDateTime now,
            @Param("snapshot") String snapshot);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(created_at,'%x-W%v') cohort
              FROM nx_user WHERE id=#{userId} AND is_deleted=0 LIMIT 1
            """)
    Attribution attribution(@Param("userId") Long userId);

    record PolicyRow(String policyKey, String currentValue) {
    }

    record TrialRow(
            Long id, Long userId, String claimNo, String status, Long userDeviceId, Long paymentMethodId,
            String deviceName, Integer durationDays, BigDecimal dailyUsdt, BigDecimal dailyNex,
            BigDecimal offsetCapUsdt, BigDecimal priceUsdt, LocalDateTime claimedAt, LocalDateTime expiresAt,
            BigDecimal shadowAccruedUsdt, BigDecimal shadowAccruedNex, BigDecimal remainderUsdt,
            BigDecimal discountUsdt, BigDecimal settlementAmountUsdt, LocalDateTime cooldownUntil, Long version) {
    }

    record WalletRow(BigDecimal usdt, BigDecimal nex) {
    }

    record Attribution(String phase, Integer accountAgeMonths, String cohort) {
    }
}
